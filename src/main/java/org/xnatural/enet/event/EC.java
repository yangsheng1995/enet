package org.xnatural.enet.event;

import org.xnatural.enet.event.EP.Listener;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件执行的上下文
 */
public class EC {
    /**
     * 一次事件执行的id. 用于追踪执行的是哪次事件
     */
    String id;
    /**
     * 是否追踪执行.用于调试
     */
    boolean track;
    /**
     * 强制异步. 如果设置了就会忽略 @EL中的设置
     */
    Boolean async;
    EP ep;
    /**
     * 事件源
     */
    private Object              source;
    /**
     * 用于临时存放, 上一个Listener的执行结果.
     * 更灵活的数据存储请用 {@link #attr(Object, Object)}
     */
    public  Object              result;
    /**
     * 用于临时存放, 上一个Listener的执行异常
     */
    public  Throwable           ex;
    /**
     * 要执行的事件链
     */
    private List<Listener>      willPass;
    /**
     * 执行过的事件链
     */
    private List<Listener>      passed = new LinkedList<>();
    private Map<Object, Object> attrs  = new ConcurrentHashMap<>(7);


    public static EC of(Object source) {
        return new EC(source);
    }
    public static EC of(Object key, Object value) {
        return new EC().attr(key, value);
    }


    public EC() {}
    public EC(Object source) {
        this.source = source;
    }


    /**
     * 此条事件执行链的要执行的所有监听
     * @param ls
     * @return
     */
    EC willPass(List<Listener> ls) {
        willPass = ls;
        return this;
    }


    /**
     * passed一个Listener 代表执行成功一个Listerner. 即: 执行成功后调用
     * @param l
     * @return
     */
    EC passed(Listener l) {
        passed.add(l);
        return this;
    }


    /**
     * 是否都执行成功
     * @return
     */
    public boolean isSuccess() {
        return willPass != null && willPass.size() == passed.size();
    }


    /**
     * 是否没有监听器
     * @return
     */
    public boolean noListener() {
        return willPass == null;
    }


    /**
     * 同步执行
     * @return
     */
    public EC sync() { async = false; return this; }


    public EP ep() { return ep; }


    public Object source() { return source; }


    public EC setSource(Object s) {
        if (willPass != null) throw new RuntimeException("事件源不允许更改");
        this.source = s;
        return this;
    }


    public EC setResult(Object result) {
        this.result = result;
        return this;
    }


    public String id() { return id; }


    public EC attr(Object key, Object value) {
        attrs.put(key, value);
        return this;
    }


    public <T> T getAttr(Object key, Class<T> type, T defaultValue) {
        return type.cast(attrs.getOrDefault(key, defaultValue));
    }


    public <T> T getAttr(Object key, Class<T> type) {
        return getAttr(key, type, null);
    }


    public Object getAttr(Object key) {
        return attrs.get(key);
    }
}