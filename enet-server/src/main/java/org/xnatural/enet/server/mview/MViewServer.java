package org.xnatural.enet.server.mview;

import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {

    /**
     * mview http path前缀. 默认为: mview
     */
    protected String path;
    protected Controller ctl;

    public MViewServer() {
        setName("mview");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", getName()));
        path = getStr("path", "mview");

        ctl = new Controller(this);
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getPath() + "/").replace("//", "/"));
        coreEp.fire("resteasy.addResource", ctl, getPath());
    }

    @Override
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
        // TODO
    }


    public String getPath() {
        return path;
    }
}
