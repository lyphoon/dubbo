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
package com.alibaba.dubbo.common.extension.factory;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * private ExtensionLoader(Class<?> type) {
 *    this.type = type;
 *    objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
 * }
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    private final List<ExtensionFactory> factories;

    /**
     * 初始化（ExtensionLoader）：
     * getAdaptiveExtension() --> createAdaptiveExtension() --> injectExtension((T) getAdaptiveExtensionClass().newInstance())
     *
     * newInstance()会执行默认构造器
     */
    public AdaptiveExtensionFactory() {
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();

        /**
         * loader.getSupportedExtensions()  -->
         *      public Set<String> getSupportedExtensions() {
         *         Map<String, Class<?>> clazzes = getExtensionClasses();
         *         return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));  //只取key
         *      }
         *  --> getExtensionClasses()
         *  --> Map<String, Class<?>> loadExtensionClasses()  //从配置文件中，加载拓展实现类数组
         *  -->
         *      Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
         *      loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
         *      loadDirectory(extensionClasses, DUBBO_DIRECTORY);
         *      loadDirectory(extensionClasses, SERVICES_DIRECTORY);
         *      return extensionClasses;
         *  --> void loadDirectory(Map<String, Class<?>> extensionClasses, String dir)
         *  --> loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL)
         *  --> void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name)  //针对每一行
         *  -->
         *      1. 将有@Adaptive注解的缓存到cachedAdaptiveClass中
         *      2. 将是Wrapper的缓存到cachedWrapperClasses中
         *      3. 正常的进行三个操作
         *          3.1 cachedActivates.put(names[0], activate);
         *          3.2 cachedNames.put(clazz, n);  //n为name按","切分的每一个名字
         *          3.3 extensionClasses.put(n, clazz)；//结果集中
         *
         *  ==》这样结果集中不会包括自适应的类，也就是facotries中不包括AdaptiveExtensionFactory自己。下面的代码（<T> T getExtension(Class<T> type, String name)）不会进入死循环
         */
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
