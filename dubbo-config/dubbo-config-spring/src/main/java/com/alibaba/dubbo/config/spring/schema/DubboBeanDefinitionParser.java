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
package com.alibaba.dubbo.config.spring.schema;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ArgumentConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 *
 * Dubbo xml解析器（DubboNamespaceHandler中使用），它继承自Spring的BeanDefinitionParser，其主要解析为其中的parse方法
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private final Class<?> beanClass;
    private final boolean required;  //是否需要在 Bean 对象的编号( id ) 不存在时，自动生成编号。无需被其他应用引用的配置对象，无需自动生成编号

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    /**
     * 解析XML元素返回BeanDefinition(它是一个接口，内部返回一个RootBeanDefinition)，测试参考相应的schema中的test,可以修改resources中的xxx.xml
     *
     * 解析xml主要做的事：将所有内容解析的key-value以PropertyValue放置在BeanDefinition的MutablePropertyValues中
     */
    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);

        /**
         *  id不存在，自动生成id
         *
         *  默认使用name，没有name时，当为ProtocalConfig时使用dubbo，其它为interface名
         *
         *  如果注册在BeanDefinitionRegistry已经存在id对就的Bean，重新生成(加数字后缀)
         */
        String id = element.getAttribute("id");
        if ((id == null || id.length() == 0) && required) {
            String generatedBeanName = element.getAttribute("name");  //name
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";  //协议，使用dubbo
                } else {
                    generatedBeanName = element.getAttribute("interface");    //其它使用interface
                }
            }
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                generatedBeanName = beanClass.getName();  //beanCalss名
            }
            id = generatedBeanName;
            int counter = 2;
            while (parserContext.getRegistry().containsBeanDefinition(id)) {  //注册在BeanDefinitionRegistry上的bean是一样的id，重新生成id
                id = generatedBeanName + (counter++);
            }
        }

        /**
         * 将BeanDefinition注册到BeanDefinitionRegistry（Spring 注册表）上，同时将id对应邀PropertyValue添加到BeanDefinition的MutablePropertyValues中
         *
         * PropertyValue是一个name,value对(只有两个属性)
         */
        if (id != null && id.length() > 0) {
            if (parserContext.getRegistry().containsBeanDefinition(id)) {  //如果在BeanDefinitionRegistry上存在同id的Bean抛出异常
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);  //将当前Bean(RootBeanDefinition)注册到BeanDefinitionRegistry
            beanDefinition.getPropertyValues().addPropertyValue("id", id);  //将id放置在BeanDefinition的MutablePropertyValues(内部有一个List<PropertyValue>)
        }


        /**
         * 以下为特殊处理逻辑（没有处理RegistryConfig, ApplicationConfig，ReferenceBean等）
         */
        if (ProtocolConfig.class.equals(beanClass)) {
            /**
             * 处理ProtocolConfig,  如果之前有bean的属性中有protocol, 并且protocol的值与ProtocalConfig相同，
             * 在找到的所有BeanDefinition中加入protocal-RuntimeBeanReference(PropertyValue)，即将ProtocalConfig的id添加到之前的BeanDefinition中
             *
             * 例如：【顺序要这样】
             *  <dubbo:service interface="com.alibaba.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
             *  <dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
             */
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {  //循环，找出注册中心的所有Bean的名称
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name); //取出对应的Bean
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol"); //取出Bean的protocol对应的PropertyValue
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));  //
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {
            /**
             * 处理ServiceBean，注意不是ServiceConfig(参考DubboNamespaceHandler)
             *
             * 处理 <dubbo:service class="" /> 场景下的处理，大多数情况下我们不这么使用，包括官方文档也没提供这种方式的说明。当配置 class 属时，会自动创建 Service Bean 对象，而无需再配置 ref 属性，指向 Service Bean 对象。示例如下：
             *
             * <bean id="demoDAO" class="com.alibaba.dubbo.demo.provider.DemoDAO" />
             * <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService"  class="com.alibaba.dubbo.demo.provider.DemoServiceImpl">
             *     <property name="demoDAO" ref="demoDAO" />
             * </dubbo:service>
             *
             * 生成class对应的RootBeanDefinition, 并解析对应的propery存入这个Bean中，最后将生成的RootBeanDefinition以ref-BeanDefinitionHolder加入
             * ServiceBean对应的RootBeanDefinition中
             */
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();  //创建另一个RootBeanDefinition, classDefinition
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition);  //解析ServiceBean中的property，存入classBeanDefinition中
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));  //将classbean以ref-BeanDefinitionHolder存入BeanDefinition中
            }
        } else if (ProviderConfig.class.equals(beanClass)) {
            /**
             * 处理ProviderConfig，解析 <dubbo:provider /> 的内嵌子元素 <dubbo:service />，参考官网schema <dubbo:provider/>,它可以嵌套<dubbo:service />
             */
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            /**
             * 处理ConsumerConfig, 解析 <dubbo:consumer /> 的内嵌子元素 <dubbo:reference />，参考官网schema <dubbo:consumer/>,它可以嵌套<dubbo:reference />
             */
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }

        /**
         * 循环Bean对象的setting方法，将属性赋值到Bean对象
         */
        Set<String> props = new HashSet<String>();  //已经解析属性集合
        ManagedMap parameters = null;  //Spring中的类，它继承处LikedHashMap并实现Mergeable接口，这里要用到Megeable功能(合并)
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {  //找到所有的属性的set方法
                Class<?> type = setter.getParameterTypes()[0];  //set方法参数类型
                String propertyName = name.substring(3, 4).toLowerCase() + name.substring(4);  //属性名
                String property = StringUtils.camelToSplitName(propertyName, "-");  //进行大写转小写处理
                props.add(property);  //将属性加入Set中
                Method getter = null;
                try {
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {  //get返回类型与set参数类型要相同，如果不相同跳过
                    continue;
                }

                /**
                 * 前面三个都是集合，在一个Bean中存在多个，特别处理
                 */
                if ("parameters".equals(property)) {
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);  //解析<dubbo:parameter />， 它返回一个ManagedMap
                } else if ("methods".equals(property)) {
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);  //解析<dubbo:method />
                } else if ("arguments".equals(property)) {
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);  // 解析<dubbo:argument />，注意它要在methods后面进行解析
                } else {
                    /**
                     * 单个属性值处理
                     */
                    String value = element.getAttribute(property);  //获取xml中对应属性的值
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                /**
                                 * 不想注册到注册中心的情况，即 `registry=N/A`
                                 */
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);  //设置RegistryConfig地址为N/A
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);  //ProperytValue:registry-RegistryConfig
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {  //xml中为registry，要转为registries
                                parseMultiRef("registries", value, beanDefinition, parserContext); // 多注册中心的情况
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("providers", value, beanDefinition, parserContext);  // 多服务提供者的情况
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("protocols", value, beanDefinition, parserContext);  // 多协议的情况
                            } else {
                                Object reference;
                                if (isPrimitive(type)) {  //原始类型
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd  兼容性处理
                                        value = null;
                                    }
                                    reference = value;
                                } else if ("protocol".equals(property)
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)
                                        && (!parserContext.getRegistry().containsBeanDefinition(value)
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {

                                    /**
                                     * 处理在 `<dubbo:provider />` 或者 `<dubbo:service />` 上定义了 `protocol` 属性的 兼容性。
                                     * 如果在provider上定义了protocol, 并且版本支持(xtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value))
                                     * 在Bean注册表中不存在protocol对应的bean(也就是<dubbo:protocol />还没有解析或者不存在这个标签)或者对应的bean类型不为ProtocolConfig
                                     *
                                     * 生成一个ProtocolConfig,将它的值设置为protocol属性值，将引用修改为protocalConfig
                                     */
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility   兼容性处理
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;
                                } else if ("onreturn".equals(property)) {
                                    /**
                                     * 参考示例 ==> 事件通知
                                     * 在调用之前、调用之后、出现异常时，会触发 oninvoke、onreturn、onthrow 三个事件
                                     */
                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);  //回调类
                                    String returnMethod = value.substring(index + 1);  //回调方法
                                    reference = new RuntimeBeanReference(returnRef);  //创建一个运行时引用，并将reference指向它
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);  //加入PropertyValues中
                                } else if ("onthrow".equals(property)) {
                                    /**
                                     * 参考示例 ==> 事件通知
                                     */
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                } else {
                                    /**
                                     * ref引用，并且注册表中有ref value对应的Bean
                                     * 也就是ref中引用的必须是一个单例Bean
                                     */
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {  //不为单例
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);  //引用
                                }
                                beanDefinition.getPropertyValues().addPropertyValue(propertyName, reference);  //将属性名-reference加入PropertyValues中， reference可以是value, ProtocolConfig, RuntimeBeanRefrernce
                            }
                        }
                    }
                }
            }
        }

        /**
         * 将 XML 元素，未在上面遍历到的属性，添加到 `parameters` 集合中。目前测试下来，不存在这样的情况
         */
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {  //还有没有解析的parameter
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }

        /**
         * 如果ManagedMap不为空， 解析<dubbo:parameter />， 它返回一个ManagedMap，当时没有将内容存入ProperyValues中，在此处统一处理
         */
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     * 解析多指向的情况，例如多注册中心，多协议等等
     *
     * 将多指向转为RuntimeBeanReference并加入到ManagedList中，将propery-ManagedList存入PropertyValues中
     * @param property 属性名
     * @param value 属性值
     * @param beanDefinition bean
     * @param parserContext 上下文
     */
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
                                      ParserContext parserContext) {
        String[] values = value.split("\\s*[,]+\\s*");  //将value以","进行切割
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));   //将切割的每一个value, 转为运行时引用
            }
        }
        beanDefinition.getPropertyValues().addPropertyValue(property, list);  //将property-MangedList加入到PropertyValues中
    }

    /**
     * 解析内嵌的子XML元素，只有两个地方用到：
     *   解析 <dubbo:provider /> 的内嵌子元素 <dubbo:service /> (
     *       parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
     *   )
     *
     *   解析 <dubbo:consumer /> 的内嵌子元素 <dubbo:reference /> (
     *      parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
     *   )
     *
     *   @param element 父 XML 元素
     *   @param parserContext Spring 解析上下文
     *   @param beanClass 内嵌解析子元素的 Bean 的类
     *   @param required 是否需要 Bean 的 `id` 属性
     *   @param tag 标签
     *   @param property 父 Bean 对象在子元素中的属性名, 在生成的子元素BeanDefinition中加入property-RuntimeBeanReference(ref)
     *   @param ref 指向
     *   @param beanDefinition 父 Bean 定义对象
     *
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {  //找到内嵌子元素标签(service/reference)
                        if (first) { //找到第一个， default??
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);  //解析子元素，注意element已经转为了node(定位到子节点)
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));  //在子元素中加入property-RuntimeBeanReference(ref)
                        }
                    }
                }
            }
        }
    }

    /**
     * 只在一个地方使用 ServiceBean中的class属性时，对class类生成一个RootBeanDefinition， 解析对应的property.
     *
     * 将propery中的name-value或者name-ref(ref转为了RuntimeBeanReference)以PropertyValue形式存入classBean中
     *
     * <bean id="demoDAO" class="com.alibaba.dubbo.demo.provider.DemoDAO" />
     *  <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService"  class="com.alibaba.dubbo.demo.provider.DemoServiceImpl">
     *       <property name="demoDAO" ref="demoDAO" />
     *  </dubbo:service>
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {  //value不为空
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);  //对生成的classBean，加入name-value
                            } else if (ref != null && ref.length() > 0) { //ref不为空
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));  //对生成的classBean, 加入name-RuntimeBeanReference(ref)
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析<dubbo:parameter />, 参考官网schema部分
     *
     * 找出parameter的key-value并存入ManagedMap中，其中value会带上类型。注意，它还没有放入到Bean中的PropertyValue中
     */
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {  //bean的子节点数组
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {  //字节点名为parameter
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = Constants.HIDE_KEY_PREFIX + key;  //如果为hide(外部不可见)，key加一个前缀
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));  //将parameter中的key-value存入ManagedMap中，其中value为TypedStringValue,它有值与值类型
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    /**
     * 解析 <dubbo:method />，参考官网Schema部分
     *
     * 将所有的method标签解析为BeanDefinition中，并转为BeanDefinitionHolder中（beanName为id.methodName）,
     * 将这些BeanDefinitionHolder放入到ManagedList中，并以PropertyValue（methods-managedList）对加入父节点中
     *
     *  @param id Bean 的 `id` 属性。
     *  @param nodeList 子元素节点数组
     *  @param beanDefinition Bean 定义对象
     *  @param parserContext 解析上下文
     */
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {  //找到method子节点
                        String methodName = element.getAttribute("name");  //方法名，它不能为空
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);  //解析method标签生成对应的BeanDefinition，注意它的element已经转为了node，并且不自动生成id
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);  //将methodBeanDefinition转为BeanDefinitionHolder,注意它的名字为id.methodname,其中id为父节点id
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods); //将所有method标签，以ManagedList加入父节点中
            }
        }
    }

    /**
     * 解析<dubbo:argument />， 参考官网Schema
     *
     * 将argument生成的BeanDefinition转为BeanDefinitionHolder(多了一个id), 并以arguments-ManagedList加入beanDefinition中
     * 其中ManagedList元素为BeanDefinitionHolder
     */
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {  //找到argument标签
                        String argumentIndex = element.getAttribute("index");  //index是必填
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);  //解析argument标签并生成对应的BeanDefinition
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);  //转为BeanDefinitionHolder, 其中id为id.argementIndex
                        arguments.add(argumentBeanDefinitionHolder);  //将BeanDefinitionHolder加入ManagedList中
                    }
                }
            }
            if (arguments != null) {
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);  //arguments-ManagedList<BeanDefinitionHolder>加入beanDefinition中
            }
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}
