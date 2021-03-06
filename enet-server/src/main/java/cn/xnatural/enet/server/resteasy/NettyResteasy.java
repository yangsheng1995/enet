package cn.xnatural.enet.server.resteasy;

import cn.xnatural.enet.common.Devourer;
import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import org.jboss.resteasy.core.InjectorFactoryImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.ValueInjector;
import org.jboss.resteasy.plugins.server.netty.*;
import org.jboss.resteasy.spi.*;
import org.jboss.resteasy.spi.metadata.Parameter;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ws.rs.Path;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.xnatural.enet.common.Utils.*;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.jboss.resteasy.plugins.server.netty.RestEasyHttpRequestDecoder.Protocol.HTTP;
import static org.jboss.resteasy.util.FindAnnotation.findAnnotation;

/**
 * netty4 和 resteasy 结合
 */
public class NettyResteasy extends ServerTpl {
    protected final AtomicBoolean      running    = new AtomicBoolean(false);
    @Resource
    protected       Executor           exec;
    /**
     * 根 path 前缀
     */
    protected       String             rootPath;
    /**
     * 表示 session 的 cookie 名字
     */
    protected       String             sessionCookieName;
    /**
     * session 是否可用
     */
    protected       boolean            enableSession;
    /**
     * see: {@link #collect()}
     */
    protected       List<Class>        scan       = new LinkedList<>();
    protected       ResteasyDeployment deployment = new ResteasyDeployment();
    protected       RequestDispatcher  dispatcher;
    /**
     * 吞噬器.请求执行控制器
     */
    protected       Devourer           devourer;
    /**
     * 关联的所有
     */
    protected       List<Object>       sources    = new LinkedList<>();


    public NettyResteasy() { super("resteasy"); }
    public NettyResteasy(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (exec == null) exec = Executors.newFixedThreadPool(2);
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", "mvc", getName()));

        enableSession = Utils.toBoolean(ep.fire("env.getAttr", "session.enabled"), false);
        rootPath = getStr("rootPath", "/");
        sessionCookieName = getStr("sessionCookieName", "sId");
        for (String c : getStr("scan", "").split(",")) {
            try {
                if (isNotBlank(c)) scan.add(Class.forName(c.trim()));
            } catch (ClassNotFoundException e) {
                log.error(e);
            }
        }
        // 初始化请求执行控制器
        devourer = new Devourer(getClass().getSimpleName(), exec);
        // 初始化resteasy组件
        startDeployment(); initDispatcher(); collect();

        ep.fire(getName() + ".started");
        log.info("Started {} Server. rootPath: {}", getName(), getRootPath());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        dispatcher = null; deployment.stop(); deployment = null;
        devourer.shutdown();
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    @EL(name = "http-netty.addHandler", async = false)
    protected void addHandler(ChannelPipeline cp) {
        initDispatcher();
        // 参考 NettyJaxrsServer
        cp.addLast(new RestEasyHttpRequestDecoder(dispatcher.getDispatcher(), rootPath, HTTP));
        cp.addLast(new RestEasyHttpResponseEncoder());
        cp.addLast(new RequestHandler(dispatcher) {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                int i = devourer.getWaitingCount();
                // 默认值: 线程池的线程个数的两倍
                if (i >= getInteger("maxWaitRequest", toInteger(invoke(findMethod(exec.getClass(), "getCorePoolSize"), exec), 10) * 2)) {
                    if (i > 0 && i % 3 == 0) log.warn("There are currently '{}' requests waiting to be processed.", i);
                    ctx.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, SERVICE_UNAVAILABLE));
                } else {
                    devourer.offer(() -> exec.execute(() -> process(ctx, msg)));
                }
            }
        });
    }


    /**
     * 处理请求
     * @param ctx
     * @param msg
     */
    protected void process(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof NettyHttpRequest) {
            NettyHttpRequest req = (NettyHttpRequest) msg;
            NettyHttpResponse resp = req.getResponse();
            try {
                if (isEnableSession()) { // 添加session控制
                    Cookie c = req.getHttpHeaders().getCookies().get(getSessionCookieName());
                    String sId;
                    if (c == null || Utils.isEmpty(c.getValue())) {
                        sId = UUID.randomUUID().toString().replace("-", "");
                        ((NettyHttpRequest) msg).getResponse().addNewCookie(
                            new NewCookie(
                                getSessionCookieName(), sId, "/", (String) null, 1, (String) null,
                                (int) TimeUnit.MINUTES.toSeconds((Integer) ep.fire("session.getExpire") + 5)
                                , null, false, false
                            )
                        );
                    } else sId = c.getValue();
                    ep.fire("session.access", sId);
                    ((NettyHttpRequest) msg).setAttribute(getSessionCookieName(), sId);
                }

                dispatcher.service(ctx, req, resp, true);
            } catch (Failure e1) {
                resp.reset(); resp.setStatus(e1.getErrorCode());
            } catch (UnhandledException e) {
                resp.reset(); resp.setStatus(500);
                if (e.getCause() != null) log.error(e.getCause());
                else log.error(e);
            } catch (Exception ex) {
                resp.reset(); resp.setStatus(500);
                log.error(ex);
            } finally {
                if (!req.getAsyncContext().isSuspended()) {
                    try { resp.finish(); }
                    catch (IOException e) { log.error(e); }
                }
                req.releaseContentBuffer();
            }
        }
    }


    /**
     * 自动注入 {@link javax.annotation.Resource}
     */
    @EL(name = "sys.started", async = false)
    protected void autoInject() {
        log.debug("auto inject @Resource field");
        sources.forEach(o -> ep.fire("inject", o));
    }


    /**
     * 添加 resteasy 接口和 Provider 资源
     * @param source
     * @return
     */
    @EL(name = {"resteasy.addResource"})
    public NettyResteasy addResource(Object source, String path, Boolean addDoc) {
        log.debug("resteasy add resource {}, path: {}", source, path);
        if (source instanceof Class) return this;
        startDeployment();
        Path pathAnno = source.getClass().getAnnotation(Path.class);
        if (pathAnno != null) {
            if (path != null) deployment.getRegistry().addSingletonResource(source, path);
            else deployment.getRegistry().addSingletonResource(source);
            if (Boolean.TRUE.equals(addDoc)) { ep.fire("openApi.addJaxrsDoc", source, path, pathAnno.value()); }
        } else if (source.getClass().getAnnotation(Provider.class) != null) {
            deployment.getProviderFactory().register(source);
        }

        ep.addListenerSource(source); sources.add(source);
        return this;
    }


    /**
     * 创建 RequestDispatcher
     */
    protected void initDispatcher() {
        if (dispatcher == null) {
            dispatcher = new RequestDispatcher((SynchronousDispatcher)deployment.getDispatcher(), deployment.getProviderFactory(), null);
        }
    }


    protected void startDeployment() {
        if (deployment == null) { deployment = new ResteasyDeployment(); }
        if (deployment.getRegistry() != null) return;
        synchronized(this) {
            if (deployment.getRegistry() != null) return;
            deployment.setInjectorFactory(new InjectorFactoryImpl() {
                @Override
                public ValueInjector createParameterExtractor(Parameter param, ResteasyProviderFactory pf) {
                    SessionAttr attrAnno = findAnnotation(param.getAnnotations(), SessionAttr.class);
                    if (findAnnotation(param.getAnnotations(), SessionId.class) != null) {
                        return new ValueInjector() {
                            @Override
                            public Object inject() { return null; }
                            @Override
                            public Object inject(HttpRequest request, HttpResponse response) {
                                return request.getAttribute(getSessionCookieName());
                            }
                        };
                    } else if (attrAnno != null) {
                        return new ValueInjector() {
                            @Override
                            public Object inject() { return null; }
                            @Override
                            public Object inject(HttpRequest request, HttpResponse response) {
                                return ep.fire("session.get", request.getAttribute(getSessionCookieName()), attrAnno.value());
                            }
                        };
                    }
                    return super.createParameterExtractor(param, pf);
                }
            });
            deployment.start();
        }
    }


    /**
     * 服务启动后自动扫描此类所在包下的 Handler({@link Path} 注解的类)
     * @param clz
     */
    public NettyResteasy scan(Class clz) {
        if (running.get()) throw new IllegalArgumentException("服务正在运行不允许更改");
        scan.add(clz);
        return this;
    }


    /**
     * 收集 {@link #scan} 类对的包下边所有的 有注解{@link Path}的类
     */
    protected void collect() {
        if (scan == null || scan.isEmpty()) return;
        log.debug("collect resteasy resource. scan: {}", scan);
        for (Class tmpClz : scan) {
            iterateClass(tmpClz.getPackage().getName(), getClass().getClassLoader(), clz -> {
                if (clz.getAnnotation(Path.class) != null || clz.getAnnotation(Provider.class) != null) {
                    try {
                        addResource(createAndInitSource(clz), null, true);
                    } catch (Exception e) {
                        log.error(e);
                    }
                }
            });
        }
    }


    /**
     * 创建和初始化 resteasy 接口source和Provider
     * @param clz
     * @return
     * @throws Exception
     */
    protected Object createAndInitSource(Class clz) throws Exception {
        Object o = clz.newInstance(); ep.fire("inject", o); // 注入@Resource注解的字段
        // 调用 PostConstruct 方法
        invoke(findMethod(clz, mm -> mm.getAnnotation(PostConstruct.class) != null), o);

        return o;
    }


    public String getRootPath() {
        return rootPath;
    }


    public NettyResteasy setRootPath(String rootPath) {
        if (running.get()) throw new RuntimeException("服务正在运行, 不充许更改");
        this.rootPath = rootPath;
        return this;
    }


    public String getSessionCookieName() {
        return sessionCookieName;
    }


    public NettyResteasy setSessionCookieName(String sessionCookieName) {
        if (running.get()) throw new RuntimeException("不允许运行时更改");
        if (sessionCookieName == null || sessionCookieName.isEmpty()) throw new NullPointerException("参数为空");
        this.sessionCookieName = sessionCookieName;
        return this;
    }


    @EL(name = "session.isEnabled", async = false)
    public boolean isEnableSession() {
        return enableSession;
    }
}
