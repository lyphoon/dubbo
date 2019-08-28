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
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import static com.alibaba.dubbo.config.spring.util.AnnotatedBeanDefinitionRegistryUtils.registerBeans;


/**
 * Dubbo {@link AbstractConfig Config} {@link ImportBeanDefinitionRegistrar register}
 *
 * 实现 ImportBeanDefinitionRegistrar 接口，处理 @EnableDubboConfig 注解，注册相应的 DubboConfigConfiguration 到 Spring 容器中
 *
 * 定义一个ImportBeanDefinitionRegistrar的实现类，然后在有@Configuration注解的配置类上使用@Import导入
 *
 * @see EnableDubboConfig
 * @see DubboConfigConfiguration
 * @see Ordered
 * @since 2.5.8
 */
public class DubboConfigConfigurationRegistrar implements ImportBeanDefinitionRegistrar {


    /**
     * Register bean definitions as necessary based on the given annotation metadata of the importing {@code @Configuration} class.
     *
     * 它允许我们直接通过BeanDefinitionRegistry对象注册bean. 参考：https://www.iteye.com/blog/elim-2430132
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        /**
         * 获取@EnableDubboConfig注解的属性值
         *
         * AnnotationAttributes继承处LikedHashMap, AnnotationMetadata为注解元数据
         *
         * 获取注解名为EnableDubboConfig.class.getName()的所有元数据，它返回一个Map，将返回的map封装为AnnotationAttributes
         */
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfig.class.getName()));

        boolean multiple = attributes.getBoolean("multiple");

        // Single Config Bindings
        registerBeans(registry, DubboConfigConfiguration.Single.class);  // 注册DubboConfigConfiguration.Single Bean

        if (multiple) { // Since 2.6.6 https://github.com/apache/incubator-dubbo/issues/3193
            registerBeans(registry, DubboConfigConfiguration.Multiple.class);  //注册DubboConfigConfiguration.Multiple Bean
        }
    }
}