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

import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.config.AbstractConfig;
import com.alibaba.dubbo.config.spring.context.annotation.DubboConfigBindingRegistrar;
import com.alibaba.dubbo.config.spring.context.annotation.EnableDubboConfigBinding;
import com.alibaba.dubbo.config.spring.context.config.DubboConfigBeanCustomizer;
import com.alibaba.dubbo.config.spring.context.properties.DefaultDubboConfigBinder;
import com.alibaba.dubbo.config.spring.context.properties.DubboConfigBinder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.springframework.beans.factory.BeanFactoryUtils.beansOfTypeIncludingAncestors;

/**
 * Dubbo Config Binding {@link BeanPostProcessor}
 *
 * DubboConfig的后置处理器（BeanPostProcessor, 初始化之前与之后两个钩子）（InitializingBean初始化钩子）
 * （ApplicationContextAware Normally this call will be used to initialize the object）
 *
 * 处理 Dubbo AbstractConfig Bean 的配置属性注入
 *
 * 参考：https://www.jianshu.com/p/80d4fa132747
 * 初始化过程中各方法的执行顺序如下：
 * 调用构造器 Bean.constructor，进行实例化;
 * 调用 Setter 方法，设置属性值;
 * 调用 BeanNameAware.setBeanName，设置Bean的ID或者Name;
 * 调用 BeanFactoryAware.setBeanFactory，设置BeanFactory;
 * 调用 ApplicationContextAware.setApplicationContext，置ApplicationContext；(1)
 * 调用BeanPostProcessor的预先初始化方法，如下：(2)
 * BeanPostProcessor1.postProcessBeforeInitialization
 * BeanPostProcessor2.postProcessBeforeInitialization
 * BeanPostProcessor3.postProcessBeforeInitialization
 * ……
 * 调用由 @PostConstruct 注解的方法；
 * 调用 InitializingBean.afterPropertiesSet；(3)
 * 调用 Bean.init-mehod 初始化方法；
 * 调用BeanPostProcessor的后初始化方法，如下：(2)
 * BeanPostProcessor1.postProcessAfterInitialization
 * BeanPostProcessor2.postProcessAfterInitialization
 * BeanPostProcessor3.postProcessAfterInitializatio
 *
 *
 * 1. 实例化;
 * 2. 设置属性值;
 * 3. 如果实现了BeanNameAware接口,调用setBeanName设置Bean的ID或者Name;
 * 4. 如果实现BeanFactoryAware接口,调用setBeanFactory 设置BeanFactory;
 * 5. 如果实现ApplicationContextAware,调用setApplicationContext设置ApplicationContext
 * 6. 调用BeanPostProcessor的预先初始化方法;
 * 7. 调用InitializingBean的afterPropertiesSet()方法;
 * 8. 调用定制init-method方法；
 * 9. 调用BeanPostProcessor的后初始化方法;
 *
 * @see EnableDubboConfigBinding
 * @see DubboConfigBindingRegistrar
 * @since 2.5.8
 */

public class DubboConfigBindingBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, InitializingBean {

    private final Log log = LogFactory.getLog(getClass());

    /**
     * The prefix of Configuration Properties
     */
    private final String prefix;

    /**
     * Binding Bean Name
     */
    private final String beanName;

    private DubboConfigBinder dubboConfigBinder;

    private ApplicationContext applicationContext;

    private boolean ignoreUnknownFields = true;

    private boolean ignoreInvalidFields = true;

    private List<DubboConfigBeanCustomizer> configBeanCustomizers = Collections.emptyList();

    /**
     * DubboConfigBindingRegistrar 类中 builder.addConstructorArgValue(actualPrefix).addConstructorArgValue(beanName); 一段的代码
     * @param prefix   the prefix of Configuration Properties  配置属性的前缀
     * @param beanName the binding Bean Name  配置类
     */
    public DubboConfigBindingBeanPostProcessor(String prefix, String beanName) {
        Assert.notNull(prefix, "The prefix of Configuration Properties must not be null");
        Assert.notNull(beanName, "The name of bean must not be null");
        this.prefix = prefix;
        this.beanName = beanName;
    }

    /**
     * 设置配置属性到 Dubbo Config 中
     *
     * ?? postProcessBeforeInitialization在afterProperties之前运行，为什么能拿到dubboConfigBinder（其中setDubboConfigBinder还没有使用到）
     *
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {

        if (beanName.equals(this.beanName) && bean instanceof AbstractConfig) {

            AbstractConfig dubboConfig = (AbstractConfig) bean;

            bind(prefix, dubboConfig);  //绑定属性值到Config Bean上, 内部使用Spring DataBinder(MutablePropertyValues)，它会将属性值设置到Config中

            customize(beanName, dubboConfig);

        }

        return bean;

    }

    private void bind(String prefix, AbstractConfig dubboConfig) {

        dubboConfigBinder.bind(prefix, dubboConfig);

        if (log.isInfoEnabled()) {
            log.info("The properties of bean [name : " + beanName + "] have been binding by prefix of " +
                    "configuration properties : " + prefix);
        }
    }

    private void customize(String beanName, AbstractConfig dubboConfig) {

        for (DubboConfigBeanCustomizer customizer : configBeanCustomizers) {
            customizer.customize(beanName, dubboConfig);  //如果name的get方法为空，将beanName作为它的name并调用set方法进行设置
        }

    }

    public boolean isIgnoreUnknownFields() {
        return ignoreUnknownFields;
    }

    public void setIgnoreUnknownFields(boolean ignoreUnknownFields) {
        this.ignoreUnknownFields = ignoreUnknownFields;
    }

    public boolean isIgnoreInvalidFields() {
        return ignoreInvalidFields;
    }

    public void setIgnoreInvalidFields(boolean ignoreInvalidFields) {
        this.ignoreInvalidFields = ignoreInvalidFields;
    }

    public DubboConfigBinder getDubboConfigBinder() {
        return dubboConfigBinder;
    }

    public void setDubboConfigBinder(DubboConfigBinder dubboConfigBinder) {  //这个还没有使用到
        this.dubboConfigBinder = dubboConfigBinder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 初始化，在构造器完成后进行
     */
    @Override
    public void afterPropertiesSet() throws Exception {

        initDubboConfigBinder();

        initConfigBeanCustomizers();

    }

    /**
     *
     * 获取DubboConfigBinder(Bind the properties to Dubbo Config Object under specified prefix)，如果没有则创建
     * 初始化
     */
    private void initDubboConfigBinder() {

        if (dubboConfigBinder == null) {
            try {
                dubboConfigBinder = applicationContext.getBean(DubboConfigBinder.class);  //在afterPropertiesSet之前已经完成了apllicationContext注入
            } catch (BeansException ignored) {
                if (log.isDebugEnabled()) {
                    log.debug("DubboConfigBinder Bean can't be found in ApplicationContext.");
                }
                // Use Default implementation
                dubboConfigBinder = createDubboConfigBinder(applicationContext.getEnvironment());
            }
        }

        dubboConfigBinder.setIgnoreUnknownFields(ignoreUnknownFields);
        dubboConfigBinder.setIgnoreInvalidFields(ignoreInvalidFields);

    }

    private void initConfigBeanCustomizers() {

        Collection<DubboConfigBeanCustomizer> configBeanCustomizers =
                beansOfTypeIncludingAncestors(applicationContext, DubboConfigBeanCustomizer.class).values();  //上下文获取所有可以拿到的DubboConfigBeanCustomizer

        this.configBeanCustomizers = new ArrayList<DubboConfigBeanCustomizer>(configBeanCustomizers);

        AnnotationAwareOrderComparator.sort(this.configBeanCustomizers); //进行排序
    }

    /**
     * Create {@link DubboConfigBinder} instance.
     *
     * @param environment
     * @return {@link DefaultDubboConfigBinder}
     */
    protected DubboConfigBinder createDubboConfigBinder(Environment environment) {
        DefaultDubboConfigBinder defaultDubboConfigBinder = new DefaultDubboConfigBinder();
        defaultDubboConfigBinder.setEnvironment(environment);
        return defaultDubboConfigBinder;
    }

}
