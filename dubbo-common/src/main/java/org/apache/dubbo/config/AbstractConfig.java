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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.InmemoryConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.FieldUtils;
import org.apache.dubbo.common.utils.MethodUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Nested;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.MethodDescriptor;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.utils.ClassUtils.isSimpleType;
import static org.apache.dubbo.common.utils.ReflectUtils.findMethodByMethodSignature;
import static org.apache.dubbo.config.Constants.PARAMETERS;

/**
 * Utility methods and public methods for parsing configuration
 *
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;

    /**
     * tag name cache, speed up get tag name frequently
     */
    private static final Map<Class, String> tagNameCache = new ConcurrentHashMap<>();

    /**
     * attributed getter method cache for equals(), hashCode() and toString()
     */
    private static final Map<Class, List<Method>> attributedMethodCache = new ConcurrentHashMap<>();

    /**
     * The suffix container
     */
    private static final String[] SUFFIXES = new String[]{"Config", "Bean", "ConfigBase"};

    /**
     * The config id
     */
    private String id;

    protected final AtomicBoolean refreshed = new AtomicBoolean(false);

    /**
     * Is default config or not
     */
    protected Boolean isDefault;

    /**
     * The scope model of this config instance.
     * <p>
     * <b>NOTE:</b> the model maybe changed during config processing,
     * the extension spi instance needs to be reinitialized after changing the model!
     */
    protected ScopeModel scopeModel;

    public AbstractConfig() {
        this(ApplicationModel.defaultModel());
    }

    public AbstractConfig(ScopeModel scopeModel) {
        this.setScopeModel(scopeModel);
    }

    public static String getTagName(Class<?> cls) {
        return tagNameCache.computeIfAbsent(cls, (key) -> {
            String tag = cls.getSimpleName();
            for (String suffix : SUFFIXES) {
                if (tag.endsWith(suffix)) {
                    tag = tag.substring(0, tag.length() - suffix.length());
                    break;
                }
            }
            return StringUtils.camelToSplitName(tag, "-");
        });
    }

    public static String getPluralTagName(Class<?> cls) {
        String tagName = getTagName(cls);
        if (tagName.endsWith("y")) {
            // e.g. registry -> registries
            return tagName.substring(0, tagName.length() - 1) + "ies";
        } else if (tagName.endsWith("s")) {
            // e.g. metrics -> metricses
            return tagName + "es";
        }
        return tagName + "s";
    }

    public static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    @SuppressWarnings("unchecked")
    public static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        appendParameters0(parameters, config, prefix, true);
    }

    /**
     * Put attributes of specify 'config' into 'parameters' argument
     *
     * @param parameters
     * @param config
     */
    public static void appendAttributes(Map<String, String> parameters, Object config) {
        appendParameters0(parameters, config, null, false);
    }

    private static void appendParameters0(Map<String, String> parameters, Object config, String prefix, boolean asParameters) {
        if (config == null) {
            return;
        }
        // If asParameters=false, it means append attributes, ignore @Parameter annotation's attributes except 'append' and 'attribute'

        // How to select the appropriate one from multiple getter methods of the property?
        // e.g. Using String getGeneric() or Boolean isGeneric()? Judge by field type ?
        // Currently, use @Parameter.attribute() to determine whether it is an attribute.

        BeanInfo beanInfo = getBeanInfo(config.getClass());
        for (MethodDescriptor methodDescriptor : beanInfo.getMethodDescriptors()) {
            Method method = methodDescriptor.getMethod();
            try {
                String name = method.getName();
                if (MethodUtils.isGetter(method)) {
                    if (method.getReturnType() == Object.class) {
                        continue;
                    }
                    String key;
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (asParameters) {
                        if (parameter != null && parameter.excluded()) {
                            continue;
                        }
                        // get parameter key
                        key = calculatePropertyFromGetter(name);
                        if (parameter != null && parameter.key().length() > 0) {
                            key = parameter.key();
                        }
                    } else { // as attributes
                        // filter non attribute
                        if (parameter != null && !parameter.attribute()) {
                            continue;
                        }
                        // get attribute name
                        String propertyName = calculateAttributeFromGetter(method.getName());
                        // convert camelCase/snake_case to kebab-case
                        key = StringUtils.convertToSplitName(propertyName, "-");
                    }
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        if (asParameters && parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        if (parameter != null && parameter.append()) {
                            String pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                                //Remove duplicate values
                                Set<String> set = StringUtils.splitToSet(str, ',');
                                str = StringUtils.join(set, ",");
                            }
                        }
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, str);
                    } else if (asParameters && parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                } else if (isParametersGetter(method)) {
                    Map<String, String> map = (Map<String, String>) method.invoke(config);
                    map = convert(map, prefix);
                    if (asParameters) {
                        // put all parameters to url
                        parameters.putAll(map);
                    } else {
                        // encode parameters to string for config overriding, see AbstractConfig#refresh()
                        String key = calculatePropertyFromGetter(name);
                        String encodeParameters = StringUtils.encodeParameters(map);
                        if (encodeParameters != null) {
                            parameters.put(key, encodeParameters);
                        }
                    }
                } else if (isNestedGetter(config, method)) {
                    Object inner = method.invoke(config);
                    String fieldName = MethodUtils.extractFieldName(method);
                    String nestedPrefix = prefix == null ? fieldName : prefix + "." + fieldName;
                    appendParameters0(parameters, inner, nestedPrefix, asParameters);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Append parameters failed: " + e.getMessage(), e);
            }
        }
    }

    protected static Set<String> getSubProperties(Map<String, String> properties, String prefix) {
        return properties.keySet().stream().filter(k -> k.contains(prefix)).map(k -> {
            k = k.substring(prefix.length());
            return k.substring(0, k.indexOf("."));
        }).collect(Collectors.toSet());
    }

    protected static String extractPropertyName(String setter) {
        String propertyName = setter.substring("set".length());
        propertyName = propertyName.substring(0, 1).toLowerCase() + propertyName.substring(1);
        return propertyName;
    }

    private static String calculatePropertyFromGetter(String name) {
        int i = name.startsWith("get") ? 3 : 2;
        return StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
    }

    private static String calculateAttributeFromGetter(String getter) {
        int i = getter.startsWith("get") ? 3 : 2;
        return getter.substring(i, i + 1).toLowerCase() + getter.substring(i + 1);
    }

    private static void invokeSetParameters(Class c, Object o, Map map) {
        try {
            Method method = findMethodByMethodSignature(c, "setParameters", new String[]{Map.class.getName()});
            if (method != null && isParametersSetter(method)) {
                method.invoke(o, map);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private static Map<String, String> invokeGetParameters(Class c, Object o) {
        try {
            Method method = findMethodByMethodSignature(c, "getParameters", null);
            if (method != null && isParametersGetter(method)) {
                return (Map<String, String>) method.invoke(o);
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private static boolean isParametersGetter(Method method) {
        String name = method.getName();
        return ("getParameters".equals(name)
            && Modifier.isPublic(method.getModifiers())
            && method.getParameterTypes().length == 0
            && method.getReturnType() == Map.class);
    }

    private static boolean isParametersSetter(Method method) {
        return ("setParameters".equals(method.getName())
            && Modifier.isPublic(method.getModifiers())
            && method.getParameterCount() == 1
            && Map.class == method.getParameterTypes()[0]
            && method.getReturnType() == void.class);
    }

    private static boolean isNestedGetter(Object obj, Method method) {
        String name = method.getName();
        boolean isGetter = (name.startsWith("get") || name.startsWith("is"))
            && !"get".equals(name) && !"is".equals(name)
            && !"getClass".equals(name) && !"getObject".equals(name)
            && Modifier.isPublic(method.getModifiers())
            && method.getParameterTypes().length == 0
            && (!method.getReturnType().isPrimitive() && !isSimpleType(method.getReturnType()));

        if (!isGetter) {
            return false;
        } else {
            // Extract fieldName only when necessary.
            String fieldName = MethodUtils.extractFieldName(method);
            Field field = FieldUtils.getDeclaredField(obj.getClass(), fieldName);
            return field != null && field.isAnnotationPresent(Nested.class);
        }
    }

    private static boolean isNestedSetter(Object obj, Method method) {
        boolean isSetter = method.getName().startsWith("set")
            && !"set".equals(method.getName())
            && Modifier.isPublic(method.getModifiers())
            && method.getParameterCount() == 1
            && method.getParameterTypes()[0] != null
            && (!method.getParameterTypes()[0].isPrimitive() && !isSimpleType(method.getParameterTypes()[0]));

        if (!isSetter) {
            return false;
        } else {
            // Extract fieldName only when necessary.
            String fieldName = MethodUtils.extractFieldName(method);
            Field field = FieldUtils.getDeclaredField(obj.getClass(), fieldName);
            return field != null && field.isAnnotationPresent(Nested.class);
        }
    }

    /**
     * @param parameters the raw parameters
     * @param prefix     the prefix
     * @return the parameters whose raw key will replace "-" to "."
     * @revised 2.7.8 "private" to be "protected"
     */
    protected static Map<String, String> convert(Map<String, String> parameters, String prefix) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>();
        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            result.put(pre + key, value);
            // For compatibility, key like "registry-type" will has a duplicate key "registry.type"
            if (Arrays.binarySearch(Constants.DOT_COMPATIBLE_KEYS, key) >= 0) {
                result.put(pre + key.replace('-', '.'), value);
            }
        }
        return result;
    }

    public ApplicationModel getApplicationModel() {
        if (scopeModel instanceof ApplicationModel) {
            return (ApplicationModel) scopeModel;
        } else if (scopeModel instanceof ModuleModel) {
            return ((ModuleModel) scopeModel).getApplicationModel();
        } else {
            throw new IllegalStateException("scope model is invalid: " + scopeModel);
        }
    }

    public ScopeModel getScopeModel() {
        return scopeModel;
    }

    public final void setScopeModel(ScopeModel scopeModel) {
        if (this.scopeModel != scopeModel) {
            checkScopeModel(scopeModel);
            ScopeModel oldScopeModel = this.scopeModel;
            this.scopeModel = scopeModel;
            // reinitialize spi extension and change referenced config's scope model
            this.postProcessAfterScopeModelChanged(oldScopeModel, this.scopeModel);
        }
    }

    protected void checkScopeModel(ScopeModel scopeModel) {
        if (scopeModel == null) {
            throw new IllegalArgumentException("scopeModel cannot be null");
        }
        if (!(scopeModel instanceof ApplicationModel)) {
            throw new IllegalArgumentException("Invalid scope model, expect to be a ApplicationModel but got: " + scopeModel);
        }
    }

    /**
     * Subclass should override this method to initialize its SPI extensions and change referenced config's scope model.
     * <p>
     * For example:
     * <pre>
     * protected void postProcessAfterScopeModelChanged() {
     *   super.postProcessAfterScopeModelChanged();
     *   // re-initialize spi extension
     *   this.protocol = this.getExtensionLoader(Protocol.class).getAdaptiveExtension();
     *   // change referenced config's scope model
     *   if (this.providerConfig != null && this.providerConfig.getScopeModel() != scopeModel) {
     *     this.providerConfig.setScopeModel(scopeModel);
     *   }
     * }
     * </pre>
     *
     * @param oldScopeModel
     * @param newScopeModel
     */
    protected void postProcessAfterScopeModelChanged(ScopeModel oldScopeModel, ScopeModel newScopeModel) {
        // remove this config from old ConfigManager
//        if (oldScopeModel != null && oldScopeModel instanceof ApplicationModel) {
//           ((ApplicationModel)oldScopeModel).getApplicationConfigManager().removeConfig(this);
//        }
    }

    protected <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (scopeModel == null) {
            throw new IllegalStateException("scopeModel is not initialized");
        }
        return scopeModel.getExtensionLoader(type);
    }

    @Parameter(excluded = true, attribute = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Copy attributes from annotation
     *
     * @param annotationClass
     * @param annotation
     */
    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                && method.getDeclaringClass() != Annotation.class
                && method.getReturnType() != void.class
                && method.getParameterTypes().length == 0
                && Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * <p>
     * <b>The new instance of the AbstractConfig subclass should return empty metadata.</b>
     * The purpose is is to get the attributes set by the user instead of the default value when the {@link #refresh()} method handles attribute overrides.
     * </p>
     *
     * <p><b>The default value of the field should be set in the {@link #checkDefault()} method</b>,
     * which will be called at the end of {@link #refresh()}, so that it will not affect the behavior of attribute overrides.</p>
     *
     * <p></p>
     * Should be called after Config was fully initialized.
     * <p>
     * Notice! This method should include all properties in the returning map, treat @Parameter differently compared to appendParameters?
     * </p>
     * // FIXME: this method should be completely replaced by appendParameters?
     * // -- Url parameter may use key, but props override only use property name. So replace it with appendAttributes().
     *
     * @see AbstractConfig#checkDefault()
     * @see AbstractConfig#appendParameters(Map, Object, String)
     */
    public Map<String, String> getMetaData() {
        Map<String, String> metaData = new HashMap<>();
        appendAttributes(metaData, this);
        return metaData;
    }

    private static BeanInfo getBeanInfo(Class cls) {
        BeanInfo beanInfo = null;
        try {
            beanInfo = Introspector.getBeanInfo(cls);
        } catch (IntrospectionException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return beanInfo;
    }

    private static boolean isWritableProperty(BeanInfo beanInfo, String key) {
        for (PropertyDescriptor propertyDescriptor : beanInfo.getPropertyDescriptors()) {
            if (key.equals(propertyDescriptor.getName())) {
                return propertyDescriptor.getWriteMethod() != null;
            }
        }
        return false;
    }

    @Parameter(excluded = true, attribute = false)
    public List<String> getPrefixes() {
        List<String> prefixes = new ArrayList<>();
        if (StringUtils.hasText(this.getId())) {
            // dubbo.{tag-name}s.{id}
            prefixes.add(CommonConstants.DUBBO + "." + getPluralTagName(this.getClass()) + "." + this.getId());
        }

        // check name
        String name = ReflectUtils.getProperty(this, "getName");
        if (StringUtils.hasText(name)) {
            // dubbo.{tag-name}s.{name}
            String prefix = CommonConstants.DUBBO + "." + getPluralTagName(this.getClass()) + "." + name;
            if (!prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }

        // dubbo.{tag-name}
        prefixes.add(getTypePrefix(this.getClass()));
        return prefixes;
    }

    public static String getTypePrefix(Class<? extends AbstractConfig> cls) {
        return CommonConstants.DUBBO + "." + getTagName(cls);
    }

    /**
     * Dubbo config property override
     * refresh()方法核心用于是处理出来preferredPrefix,其他的配置信息也会一并做一些处理
     * preferredPrefix是dubbo.service.org.apache.dubbo.demo.DemoService等，用dubbo.service.org.apache.dubbo.demo+接口名称
     */
    public void refresh() {
        refreshed.set(true);
        try {
            // check and init before do refresh
            preProcessRefresh();

            Environment environment = getScopeModel().getModelEnvironment();
            List<Map<String, String>> configurationMaps = environment.getConfigurationMaps();

            // Search props starts with PREFIX in order
            String preferredPrefix = null;
            for (String prefix : getPrefixes()) {
                if (ConfigurationUtils.hasSubProperties(configurationMaps, prefix)) {
                    preferredPrefix = prefix;
                    break;
                }
            }
            if (preferredPrefix == null) {
                preferredPrefix = getPrefixes().get(0);
            }
            // Extract sub props (which key was starts with preferredPrefix)
            Collection<Map<String, String>> instanceConfigMaps = environment.getConfigurationMaps(this, preferredPrefix);
            Map<String, String> subProperties = ConfigurationUtils.getSubProperties(instanceConfigMaps, preferredPrefix);
            InmemoryConfiguration subPropsConfiguration = new InmemoryConfiguration(subProperties);

            if (logger.isDebugEnabled()) {
                String idOrName = "";
                if (StringUtils.hasText(this.getId())) {
                    idOrName = "[id=" + this.getId() + "]";
                } else {
                    String name = ReflectUtils.getProperty(this, "getName");
                    if (StringUtils.hasText(name)) {
                        idOrName = "[name=" + name + "]";
                    }
                }
                logger.debug("Refreshing " + this.getClass().getSimpleName() + idOrName +
                    " with prefix [" + preferredPrefix +
                    "], extracted props: " + subProperties);
            }

            assignProperties(this, environment, subProperties, subPropsConfiguration);

            // process extra refresh of sub class, e.g. refresh method configs
            processExtraRefresh(preferredPrefix, subPropsConfiguration);

        } catch (Exception e) {
            logger.error("Failed to override field value of config bean: " + this, e);
            throw new IllegalStateException("Failed to override field value of config bean: " + this, e);
        }

        postProcessRefresh();
    }

    private void assignProperties(Object obj, Environment environment, Map<String, String> properties, InmemoryConfiguration configuration) {
        // loop methods, get override value and set the new value back to method
        Method[] methods = obj.getClass().getMethods();
        for (Method method : methods) {
            if (MethodUtils.isSetter(method)) {
                String propertyName = extractPropertyName(method.getName());
                // convert camelCase/snake_case to kebab-case
                String kebabPropertyName = StringUtils.convertToSplitName(propertyName, "-");

                try {
                    String value = StringUtils.trim(configuration.getString(kebabPropertyName));
                    // isTypeMatch() is called to avoid duplicate and incorrect update, for example, we have two 'setGeneric' methods in ReferenceConfig.
                    if (StringUtils.hasText(value) && ClassUtils.isTypeMatch(method.getParameterTypes()[0], value) &&
                        !isIgnoredAttribute(obj.getClass(), propertyName)) {
                        value = environment.resolvePlaceholders(value);
                        method.invoke(obj, ClassUtils.convertPrimitive(method.getParameterTypes()[0], value));
                    }
                } catch (Exception e) {
                    logger.info("Failed to override the property " + method.getName() + " in " +
                        obj.getClass().getSimpleName() +
                        ", please make sure every property has getter/setter method provided.");
                }
            } else if (isParametersSetter(method)) {
                String propertyName = extractPropertyName(method.getName());
                String value = StringUtils.trim(configuration.getString(propertyName));
                Map<String, String> parameterMap = null;
                if (StringUtils.hasText(value)) {
                    parameterMap = StringUtils.parseParameters(value);
                } else {
                    // in this case, maybe parameters.item3=value3.
                    parameterMap = ConfigurationUtils.getSubProperties(properties, PARAMETERS);
                }
                invokeSetParameters(convert(parameterMap, ""), obj);
            } else if (isNestedSetter(obj, method)) {
                try {
                    Class<?> clazz = method.getParameterTypes()[0];
                    Object inner = clazz.getDeclaredConstructor().newInstance();
                    String fieldName = MethodUtils.extractFieldName(method);
                    Map<String, String> subProperties = ConfigurationUtils.getSubProperties(properties, fieldName);
                    InmemoryConfiguration subPropsConfiguration = new InmemoryConfiguration(subProperties);
                    assignProperties(inner, environment, subProperties, subPropsConfiguration);
                    method.invoke(obj, inner);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot assign nested class when refreshing config: " + obj.getClass().getName(), e);
                }
            }
        }
    }

    private void invokeSetParameters(Map<String, String> values, Object obj) {
        if (CollectionUtils.isEmptyMap(values)) {
            return;
        }
        Map<String, String> map = invokeGetParameters(obj.getClass(), obj);
        map = map == null ? new HashMap<>() : map;
        map.putAll(values);
        invokeSetParameters(obj.getClass(), obj, map);
    }

    private boolean isIgnoredAttribute(Class<?> clazz, String propertyName) {
        Method getter = null;
        String capitalizePropertyName = propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
        try {
            getter = clazz.getMethod("get" + capitalizePropertyName);
        } catch (NoSuchMethodException e) {
            try {
                getter = clazz.getMethod("is" + capitalizePropertyName);
            } catch (NoSuchMethodException ex) {
            }
        }

        if (getter == null) {
            // no getter method
            return true;
        }

        Parameter parameter = getter.getAnnotation(Parameter.class);
        // not an attribute
        return parameter != null && !parameter.attribute();
    }

    protected void processExtraRefresh(String preferredPrefix, InmemoryConfiguration subPropsConfiguration) {
        // process extra refresh
    }

    protected void preProcessRefresh() {
        // pre-process refresh
    }

    protected void postProcessRefresh() {
        // post-process refresh
        checkDefault();
    }

    /**
     * Check and set default value for some fields.
     * <p>
     * This method will be called at the end of {@link #refresh()}, as a post-initializer.
     * </p>
     * <p>NOTE: </p>
     * <p>
     * To distinguish between user-set property values and default property values,
     * do not initialize default value at field declare statement. <b>If the field has a default value,
     * it should be set in the checkDefault() method</b>, which will be called at the end of {@link #refresh()},
     * so that it will not affect the behavior of attribute overrides.</p>
     *
     * @see AbstractConfig#getMetaData()
     * @see AbstractConfig#appendAttributes(Map, Object)
     */
    protected void checkDefault() {
    }

    @Parameter(excluded = true, attribute = false)
    public boolean isRefreshed() {
        return refreshed.get();
    }

    /**
     * FIXME check @Parameter(required=true) and any conditions that need to match.
     */
    @Parameter(excluded = true, attribute = false)
    public boolean isValid() {
        return true;
    }

    @Parameter(excluded = true, attribute = false)
    public Boolean isDefault() {
        return isDefault;
    }

    public void setDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    @Override
    public String toString() {
        try {

            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            for (Method method : getAttributedMethods()) {
                try {
                    if (MethodUtils.isGetter(method)) {
                        String name = method.getName();
                        String key = calculateAttributeFromGetter(name);
                        Object value = method.invoke(this);
                        if (value != null) {
                            buf.append(' ');
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(key.equals("password") ? "******" : value);
                            buf.append('\"');
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        if (obj == this) {
            return true;
        }

        for (Method method : getAttributedMethods()) {
            // ignore compare 'id' value
            if ("getId".equals(method.getName())) {
                continue;
            }
            try {
                Object value1 = method.invoke(this);
                Object value2 = method.invoke(obj);
                if (!Objects.equals(value1, value2)) {
                    return false;
                }
            } catch (Exception e) {
                throw new IllegalStateException("compare config instances failed", e);
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = 1;

        for (Method method : getAttributedMethods()) {
            // ignore compare 'id' value
            if ("getId".equals(method.getName())) {
                continue;
            }
            try {
                Object value = method.invoke(this);
                if (value != null) {
                    hashCode = 31 * hashCode + value.hashCode();
                }
            } catch (Exception ignored) {
                //ignored
            }
        }

        if (hashCode == 0) {
            hashCode = 1;
        }
        return hashCode;
    }

    private List<Method> getAttributedMethods() {
        Class<? extends AbstractConfig> cls = this.getClass();
        return attributedMethodCache.computeIfAbsent(cls, (key) -> computeAttributedMethods());
    }

    /**
     * compute attributed getter methods, subclass can override this method to add/remove attributed methods
     *
     * @return
     */
    protected List<Method> computeAttributedMethods() {
        Class<? extends AbstractConfig> cls = this.getClass();
        BeanInfo beanInfo = getBeanInfo(cls);
        List<Method> methods = new ArrayList<>(beanInfo.getMethodDescriptors().length);
        for (MethodDescriptor methodDescriptor : beanInfo.getMethodDescriptors()) {
            Method method = methodDescriptor.getMethod();
            if (MethodUtils.isGetter(method)) {
                // filter non attribute
                Parameter parameter = method.getAnnotation(Parameter.class);
                if (parameter != null && !parameter.attribute()) {
                    continue;
                }
                String propertyName = calculateAttributeFromGetter(method.getName());
                // filter non writable property, exclude non property methods, fix #4225
                if (!isWritableProperty(beanInfo, propertyName)) {
                    continue;
                }
                methods.add(method);
            }
        }
        return methods;
    }

    protected ConfigManager getConfigManager() {
        return getApplicationModel().getApplicationConfigManager();
    }
}