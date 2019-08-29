/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import com.alibaba.dubbo.config.spring.util.AnnotationUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 *
 *  扫描 @Reference 注解的类，创建对应的 Spring BeanDefinition 对象，从而创建 Dubbo Reference Bean 对象
 *
 *  AnnotationInjectedBeanPostProcessor<Reference>：ReferenceAnnotationBeanPostProcessor 实现的就是 支持 @Reference 注解的属性注入。
 *
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor<Reference>
        implements ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<String, ReferenceBean<?>>(CACHE_SIZE);

    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache =
            new ConcurrentHashMap<String, ReferenceBeanInvocationHandler>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private ApplicationContext applicationContext;

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }


    /**
     * 主方法，获得要注入的 @Reference Bean
     *
     * ??为什么只获取了一个
     */
    @Override
    protected Object doGetInjectedBean(Reference reference, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {

        String referencedBeanName = buildReferencedBeanName(reference, injectedType);  //获取Reference Bean名，与Service Bean是同一套规则

        /**
         * ReferenceBean继承ReferenceConfig, 实现了InitializingBean， 在初始化时设置各个Conifg(如ApplicationConfig)到ReferenceBean中
         */
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referencedBeanName, reference, injectedType, getClassLoader()); //创建（获得） ReferenceBean 对象

        cacheInjectedReferenceBean(referenceBean, injectedElement);

        Object proxy = buildProxy(referencedBeanName, referenceBean, injectedType);  //创建 Proxy 代理对象

        return proxy;
    }

    private Object buildProxy(String referencedBeanName, ReferenceBean referenceBean, Class<?> injectedType) {
        InvocationHandler handler = buildInvocationHandler(referencedBeanName, referenceBean);
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{injectedType}, handler);  //代理类
        return proxy;
    }

    /**
     * InvocationHandler 为java动态代理接口
     *
     *
     */
    private InvocationHandler buildInvocationHandler(String referencedBeanName, ReferenceBean referenceBean) {

        /**
         * ReferenceBeanInvocationHandler implements InvocationHandler, 实现了动态代理接口，处理ReferenceBean代理
         *
         * 从缓存map中取ReferenceBeanInvocationHandler
         */
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.get(referencedBeanName);

        if (handler == null) {
            handler = new ReferenceBeanInvocationHandler(referenceBean); //创建一个
        }

        /**
         * 代理分为本地与远程
         *
         * 判断如果 applicationContext 中已经初始化，说明是本地的 @Service Bean ，则添加到 localReferenceBeanInvocationHandlerCache 缓存中。
         */
        if (applicationContext.containsBean(referencedBeanName)) { // Is local @Service Bean or not , 是否为本地， 本地在applicatinContext中能拿到
            // ReferenceBeanInvocationHandler's initialization has to wait for current local @Service Bean has been exported.
            //等到本地的 @Service Bean 暴露后，再进行初始化, 等后续的，通过 Spring 事件监听的功能，进行实现, onApplicationEvent」
            localReferenceBeanInvocationHandlerCache.put(referencedBeanName, handler);
        } else {
            // Remote Reference Bean should initialize immediately
            handler.init();  //判断若果 applicationContext 中未初始化，说明是远程的 @Service Bean 对象，则立即进行初始化
        }

        return handler;
    }

    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        private final ReferenceBean referenceBean;  //引用对象

        private Object bean;  //ref

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;  //设置
        }

        /**
         * Processes a method invocation on a proxy instance and return the result
         *
         * 代理要实现的方法，
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = null;
            try {
                if (bean == null) { // If the bean is not initialized, invoke init()
                    // issue: https://github.com/apache/incubator-dubbo/issues/3429
                    init();  //没有初始化，进行初始化
                }
                result = method.invoke(bean, args);  //调用代理对象的invoke
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        private void init() {
            this.bean = referenceBean.get();  //获取ReferenceBean中的ref, ReferenceConfig中：ref = createProxy(map);
        }
    }

    /**
     * 主方法
     */
    @Override
    protected String buildInjectedObjectCacheKey(Reference reference, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {

        String key = buildReferencedBeanName(reference, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.getAttributes(reference, getEnvironment(), true);

        return key;
    }

    private String buildReferencedBeanName(Reference reference, Class<?> injectedType) {

        /**
         * 使用的就是 ServiceBeanNameBuilder 的逻辑，即和 Dubbo Service Bean 的名字，是 同一套。这个也非常合理
         *
         * ServiceAnnotationBeanPostProcessor.registerServiceBean()->generateServiceBeanName()->ServiceBeanNameBuilder.create(service, interfaceClass, environment)
         */
        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(reference, injectedType, getEnvironment());

        return getEnvironment().resolvePlaceholders(builder.build());  //使用enviroment解决占位符
    }

    /**
     * 获取 Reference Bean
     */
    private ReferenceBean buildReferenceBeanIfAbsent(String referencedBeanName, Reference reference,
                                                     Class<?> referencedType, ClassLoader classLoader)
            throws Exception {

        ReferenceBean<?> referenceBean = referenceBeanCache.get(referencedBeanName);  //从map中取出referencedBeanName对应的ReferenceBean

        if (referenceBean == null) {  //map中为空，也就是还没有创建过这个bean，创建并存入map中
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(reference, classLoader, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();  //已经设置了属性值到ReferenceBean中了
            referenceBeanCache.put(referencedBeanName, referenceBean);
        }

        return referenceBean;
    }

    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {  //字段
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {  //方法
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 监听Spring容器事件
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ServiceBeanExportedEvent) {
            /**
             * 如果事件为Service Bean暴露事件, ServiceBean中有一个暴露方法export，内部会发起ServiceBeanExportedEvent事件
             */
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            /**
             * ContextRefreshedEvent: Event raised when an {@code ApplicationContext} gets initialized or refreshed.
             * 在上下文初始化或者刷新时
             */
            onContextRefreshedEvent((ContextRefreshedEvent) event);  //内部没有处理逻辑
        }
    }

    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        ServiceBean serviceBean = event.getServiceBean();  //获取事件中的ServiceBean
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        // Remove ServiceBean when it's exported
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);  //从 localReferenceBeanInvocationHandlerCache 缓存中，移除
        // Initialize
        if (handler != null) {
            handler.init();  //初始化（本地中，serviceBeanName与Reference中相同）
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }


    @Override
    public void destroy() throws Exception {
        super.destroy();
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}
