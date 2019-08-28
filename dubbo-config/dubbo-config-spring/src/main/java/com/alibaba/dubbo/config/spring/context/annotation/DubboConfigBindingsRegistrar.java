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
package com.alibaba.dubbo.config.spring.context.annotation;

import com.alibaba.dubbo.config.AbstractConfig;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;

/**
 * {@link AbstractConfig Dubbo Config} binding Bean registrar for {@link EnableDubboConfigBindings}
 *
 * 实现 ImportBeanDefinitionRegistrar、EnvironmentAware 接口，处理 @EnableDubboConfigBindings 注解，
 * 注册相应的 Dubbo AbstractConfig 到 Spring 容器中
 *
 * @see EnableDubboConfigBindings
 * @see DubboConfigBindingRegistrar
 * @since 2.5.8
 */
public class DubboConfigBindingsRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private ConfigurableEnvironment environment;

    /**
     * 在 DubboConfigConfiguration 类中使用
     *
     * @EnableDubboConfigBindingsr 导入类。ImportBeanDefinitionRegistrar接口方法，注册Bean，它会在setEnviroment之后调用
     *
     * 主要是@EnableDubboConfigBindings内部的多个@EnableDubboConfigBinding元素，
     * 初始化一个@EnableDubboConfigBinding的导入类DubboConfigBindingRegistrar，将内部@EnableDubboConfigBinding所有元数据
     * 中的类，注册到Spring中
     *
     *  @EnableDubboConfigBinding(prefix = "dubbo.application", type = ApplicationConfig.class)
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        //获取@EnableDubboConfigBindings注解对应的元数据
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfigBindings.class.getName()));

        //获取value数组，在@EnableDubboConfigBindings中，每一个元素为EnableDubboConfigBinding
        AnnotationAttributes[] annotationAttributes = attributes.getAnnotationArray("value");

        DubboConfigBindingRegistrar registrar = new DubboConfigBindingRegistrar();  //EnableDubboConfigBinding接口的@Import
        registrar.setEnvironment(environment); //设置子DubboConfigBindingRegistrar中的Enviroment

        for (AnnotationAttributes element : annotationAttributes) {
            //将每一个子元素通过DubboConfigBindingRegistrar中的reisterBeanDefinitions注册到Spring容器中
            registrar.registerBeanDefinitions(element, registry);

        }
    }

    /**
     * Interface to be implemented by any bean that wishes to be notified of the {@link Environment} that it runs in.
     *
     * 获取环境通知
     */
    @Override
    public void setEnvironment(Environment environment) {

        Assert.isInstanceOf(ConfigurableEnvironment.class, environment);  //enviroment是ConfigurableEnviroment的一个实例，不是，内部会抛出异常

        this.environment = (ConfigurableEnvironment) environment;

    }

}
