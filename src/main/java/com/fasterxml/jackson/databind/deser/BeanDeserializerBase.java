package com.fasterxml.jackson.databind.deser;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerator;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.impl.*;
import com.fasterxml.jackson.databind.deser.std.ContainerDeserializerBase;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.type.ClassKey;
import com.fasterxml.jackson.databind.util.*;

/**
 * Base class for <code>BeanDeserializer</code>.
 */
public abstract class BeanDeserializerBase
    extends StdDeserializer<Object>
    implements ContextualDeserializer, ResolvableDeserializer
{
    /*
    /**********************************************************
    /* Information regarding type being deserialized
    /**********************************************************
     */

    /**
     * Annotations from the bean class: used for accessing
     * annotations during resolution phase (see {@link #resolve}).
     */
    final private Annotations _classAnnotations;
    
    /**
     * Declared type of the bean this deserializer handles.
     */
    final protected JavaType _beanType;

    /**
     * Requested shape from bean class annotations.
     */
    final protected JsonFormat.Shape _serializationShape;
    
    /*
    /**********************************************************
    /* Configuration for creating value instance
    /**********************************************************
     */

    /**
     * Object that handles details of constructing initial 
     * bean value (to which bind data to), unless instance
     * is passed (via updateValue())
     */
    protected final ValueInstantiator _valueInstantiator;
    
    /**
     * Deserializer that is used iff delegate-based creator is
     * to be used for deserializing from JSON Object.
     */
    protected JsonDeserializer<Object> _delegateDeserializer;
    
    /**
     * If the bean needs to be instantiated using constructor
     * or factory method
     * that takes one or more named properties as argument(s),
     * this creator is used for instantiation.
     * This value gets resolved during general resolution.
     */
    protected PropertyBasedCreator _propertyBasedCreator;

    /**
     * Flag that is set to mark "non-standard" cases; where either
     * we use one of non-default creators, or there are unwrapped
     * values to consider.
     */
    protected boolean _nonStandardCreation;

    /**
     * Flag that indicates that no "special features" whatsoever
     * are enabled, so the simplest processing is possible.
     */
    protected boolean _vanillaProcessing;

    /*
    /**********************************************************
    /* Property information, setters
    /**********************************************************
     */

    /**
     * Mapping of property names to properties, built when all properties
     * to use have been successfully resolved.
     */
    final protected BeanPropertyMap _beanProperties;
    
    /**
     * List of {@link ValueInjector}s, if any injectable values are
     * expected by the bean; otherwise null.
     * This includes injectors used for injecting values via setters
     * and fields, but not ones passed through constructor parameters.
     */
    final protected ValueInjector[] _injectables;
    
    /**
     * Fallback setter used for handling any properties that are not
     * mapped to regular setters. If setter is not null, it will be
     * called once for each such property.
     */
    protected SettableAnyProperty _anySetter;

    /**
     * In addition to properties that are set, we will also keep
     * track of recognized but ignorable properties: these will
     * be skipped without errors or warnings.
     */
    final protected HashSet<String> _ignorableProps;

    /**
     * Flag that can be set to ignore and skip unknown properties.
     * If set, will not throw an exception for unknown properties.
     */
    final protected boolean _ignoreAllUnknown;

    /**
     * Flag that indicates that some aspect of deserialization depends
     * on active view used (if any)
     */
    final protected boolean _needViewProcesing;
    
    /**
     * We may also have one or more back reference fields (usually
     * zero or one).
     */
    final protected Map<String, SettableBeanProperty> _backRefs;
    
    /*
    /**********************************************************
    /* Related handlers
    /**********************************************************
     */

    /**
     * Lazily constructed map used to contain deserializers needed
     * for polymorphic subtypes.
     * Note that this is <b>only needed</b> for polymorphic types,
     * that is, when the actual type is not statically known.
     * For other types this remains null.
     */
    protected HashMap<ClassKey, JsonDeserializer<Object>> _subDeserializers;

    /**
     * If one of properties has "unwrapped" value, we need separate
     * helper object
     */
    protected UnwrappedPropertyHandler _unwrappedPropertyHandler;

    /**
     * Handler that we need iff any of properties uses external
     * type id.
     */
    protected ExternalTypeHandler _externalTypeIdHandler;

    /**
     * If an Object Id is to be used for value handled by this
     * deserializer, this reader is used for handling.
     */
    protected final ObjectIdReader _objectIdReader;

    /*
    /**********************************************************
    /* Life-cycle, construction, initialization
    /**********************************************************
     */

    /**
     * Constructor used when initially building a deserializer
     * instance, given a {@link BeanDeserializerBuilder} that
     * contains configuration.
     */
    protected BeanDeserializerBase(BeanDeserializerBuilder builder,
            BeanDescription beanDesc,
            BeanPropertyMap properties, Map<String, SettableBeanProperty> backRefs,
            HashSet<String> ignorableProps, boolean ignoreAllUnknown,
            boolean hasViews)
    {
        super(beanDesc.getType());

        AnnotatedClass ac = beanDesc.getClassInfo();
        _classAnnotations = ac.getAnnotations();       
        _beanType = beanDesc.getType();
        _valueInstantiator = builder.getValueInstantiator();
        
        _beanProperties = properties;
        _backRefs = backRefs;
        _ignorableProps = ignorableProps;
        _ignoreAllUnknown = ignoreAllUnknown;

        _anySetter = builder.getAnySetter();
        List<ValueInjector> injectables = builder.getInjectables();
        _injectables = (injectables == null || injectables.isEmpty()) ? null
                : injectables.toArray(new ValueInjector[injectables.size()]);
        _objectIdReader = builder.getObjectIdReader();
        _nonStandardCreation = (_unwrappedPropertyHandler != null)
            || _valueInstantiator.canCreateUsingDelegate()
            || _valueInstantiator.canCreateFromObjectWith()
            || !_valueInstantiator.canCreateUsingDefault()
            ;

        // Any transformation we may need to apply?
        JsonFormat.Value format = beanDesc.findExpectedFormat(null);
        _serializationShape = (format == null) ? null : format.getShape();
        
        _needViewProcesing = hasViews;
        _vanillaProcessing = !_nonStandardCreation
                && (_injectables == null)
                && !_needViewProcesing
                // also, may need to reorder stuff if we expect Object Id:
                && (_objectIdReader != null)
                ;
    }

    protected BeanDeserializerBase(BeanDeserializerBase src)
    {
        this(src, src._ignoreAllUnknown);
    }

    protected BeanDeserializerBase(BeanDeserializerBase src, boolean ignoreAllUnknown)
    {
        super(src._beanType);
        
        _classAnnotations = src._classAnnotations;
        _beanType = src._beanType;
        
        _valueInstantiator = src._valueInstantiator;
        _delegateDeserializer = src._delegateDeserializer;
        _propertyBasedCreator = src._propertyBasedCreator;
        
        _beanProperties = src._beanProperties;
        _backRefs = src._backRefs;
        _ignorableProps = src._ignorableProps;
        _ignoreAllUnknown = ignoreAllUnknown;
        _anySetter = src._anySetter;
        _injectables = src._injectables;
        _objectIdReader = src._objectIdReader;
        
        _nonStandardCreation = src._nonStandardCreation;
        _unwrappedPropertyHandler = src._unwrappedPropertyHandler;
        _needViewProcesing = src._needViewProcesing;
        _serializationShape = src._serializationShape;

        _vanillaProcessing = src._vanillaProcessing;
    }
 
    protected BeanDeserializerBase(BeanDeserializerBase src, NameTransformer unwrapper)
    {
        super(src._beanType);

        _classAnnotations = src._classAnnotations;
        _beanType = src._beanType;
        
        _valueInstantiator = src._valueInstantiator;
        _delegateDeserializer = src._delegateDeserializer;
        _propertyBasedCreator = src._propertyBasedCreator;
        
        _backRefs = src._backRefs;
        _ignorableProps = src._ignorableProps;
        _ignoreAllUnknown = (unwrapper != null) || src._ignoreAllUnknown;
        _anySetter = src._anySetter;
        _injectables = src._injectables;
        _objectIdReader = src._objectIdReader;

        _nonStandardCreation = src._nonStandardCreation;
        _unwrappedPropertyHandler = src._unwrappedPropertyHandler;

        if (unwrapper != null) {
            // delegate further unwraps, if any
            if (_unwrappedPropertyHandler != null) { // got handler, delegate
                _unwrappedPropertyHandler.renameAll(unwrapper);
            }
            // and handle direct unwrapping as well:
            _beanProperties = src._beanProperties.renameAll(unwrapper);
        } else {
            _beanProperties = src._beanProperties;
        }
        _needViewProcesing = src._needViewProcesing;
        _serializationShape = src._serializationShape;

        // probably adds a twist, so:
        _vanillaProcessing = false;
    }

    public BeanDeserializerBase(BeanDeserializerBase src, ObjectIdReader oir)
    {
        super(src._beanType);
        
        _classAnnotations = src._classAnnotations;
        _beanType = src._beanType;
        
        _valueInstantiator = src._valueInstantiator;
        _delegateDeserializer = src._delegateDeserializer;
        _propertyBasedCreator = src._propertyBasedCreator;
        
        _backRefs = src._backRefs;
        _ignorableProps = src._ignorableProps;
        _ignoreAllUnknown = src._ignoreAllUnknown;
        _anySetter = src._anySetter;
        _injectables = src._injectables;
        
        _nonStandardCreation = src._nonStandardCreation;
        _unwrappedPropertyHandler = src._unwrappedPropertyHandler;
        _needViewProcesing = src._needViewProcesing;
        _serializationShape = src._serializationShape;

        _vanillaProcessing = src._vanillaProcessing;

        // then actual changes:
        _objectIdReader = oir;

        if (oir == null) {
            _beanProperties = src._beanProperties;
        } else {
            _beanProperties = src._beanProperties.withProperty(new ObjectIdValueProperty(oir));
        }
    }

    public BeanDeserializerBase(BeanDeserializerBase src, HashSet<String> ignorableProps)
    {
        super(src._beanType);
        
        _classAnnotations = src._classAnnotations;
        _beanType = src._beanType;
        
        _valueInstantiator = src._valueInstantiator;
        _delegateDeserializer = src._delegateDeserializer;
        _propertyBasedCreator = src._propertyBasedCreator;
        
        _backRefs = src._backRefs;
        _ignorableProps = ignorableProps;
        _ignoreAllUnknown = src._ignoreAllUnknown;
        _anySetter = src._anySetter;
        _injectables = src._injectables;
        
        _nonStandardCreation = src._nonStandardCreation;
        _unwrappedPropertyHandler = src._unwrappedPropertyHandler;
        _needViewProcesing = src._needViewProcesing;
        _serializationShape = src._serializationShape;

        _vanillaProcessing = src._vanillaProcessing;
        _objectIdReader = src._objectIdReader;
        _beanProperties = src._beanProperties;
    }
    
    @Override
    public abstract JsonDeserializer<Object> unwrappingDeserializer(NameTransformer unwrapper);

    public abstract BeanDeserializerBase withObjectIdReader(ObjectIdReader oir);

    public abstract BeanDeserializerBase withIgnorableProperties(HashSet<String> ignorableProps);

    /**
     * Fluent factory for creating a variant that can handle
     * POJO output as a JSON Array. Implementations may ignore this request
     * if no such input is possible.
     * 
     * @since 2.1
     */
    protected abstract BeanDeserializerBase asArrayDeserializer();
    
    /*
    /**********************************************************
    /* Validation, post-processing
    /**********************************************************
     */

    /**
     * Method called to finalize setup of this deserializer,
     * after deserializer itself has been registered.
     * This is needed to handle recursive and transitive dependencies.
     */
//  @Override
    public void resolve(DeserializationContext ctxt)
        throws JsonMappingException
    {
        ExternalTypeHandler.Builder extTypes = null;
        // if ValueInstantiator can use "creator" approach, need to resolve it here...
        if (_valueInstantiator.canCreateFromObjectWith()) {
            SettableBeanProperty[] creatorProps = _valueInstantiator.getFromObjectArguments(ctxt.getConfig());
            _propertyBasedCreator = PropertyBasedCreator.construct(ctxt, _valueInstantiator, creatorProps);
            // also: need to try to resolve 'external' type ids...
            for (SettableBeanProperty prop : _propertyBasedCreator.properties()) {
                if (prop.hasValueTypeDeserializer()) {
                    TypeDeserializer typeDeser = prop.getValueTypeDeserializer();
                    if (typeDeser.getTypeInclusion() == JsonTypeInfo.As.EXTERNAL_PROPERTY) {
                        if (extTypes == null) {
                            extTypes = new ExternalTypeHandler.Builder();
                        }
                        extTypes.addExternal(prop, typeDeser.getPropertyName());
                    }
                }
            }
        }

        UnwrappedPropertyHandler unwrapped = null;

        for (SettableBeanProperty origProp : _beanProperties) {
            SettableBeanProperty prop = origProp;
            // May already have deserializer from annotations, if so, skip:
            if (!prop.hasValueDeserializer()) {
                prop = prop.withValueDeserializer(findDeserializer(ctxt, prop.getType(), prop));
            } else { // may need contextual version
                JsonDeserializer<Object> deser = prop.getValueDeserializer();
                if (deser instanceof ContextualDeserializer) {
                    JsonDeserializer<?> cd = ((ContextualDeserializer) deser).createContextual(ctxt, prop);
                    if (cd != deser) {
                        prop = prop.withValueDeserializer(cd);
                    }
                }
            }
            // [JACKSON-235]: need to link managed references with matching back references
            prop = _resolveManagedReferenceProperty(ctxt, prop);
            // [JACKSON-132]: support unwrapped values (via @JsonUnwrapped)
            SettableBeanProperty u = _resolveUnwrappedProperty(ctxt, prop);
            if (u != null) {
                prop = u;
                if (unwrapped == null) {
                    unwrapped = new UnwrappedPropertyHandler();
                }
                unwrapped.addProperty(prop);
                continue;
            }
            // [JACKSON-594]: non-static inner classes too:
            prop = _resolveInnerClassValuedProperty(ctxt, prop);
            if (prop != origProp) {
                _beanProperties.replace(prop);
            }
            
            /* one more thing: if this property uses "external property" type inclusion
             * (see [JACKSON-453]), it needs different handling altogether
             */
            if (prop.hasValueTypeDeserializer()) {
                TypeDeserializer typeDeser = prop.getValueTypeDeserializer();
                if (typeDeser.getTypeInclusion() == JsonTypeInfo.As.EXTERNAL_PROPERTY) {
                    if (extTypes == null) {
                        extTypes = new ExternalTypeHandler.Builder();
                    }
                    extTypes.addExternal(prop, typeDeser.getPropertyName());
                    // In fact, remove from list of known properties to simplify later handling
                    _beanProperties.remove(prop);
                    continue;
                }
            }
        }

        // "any setter" may also need to be resolved now
        if (_anySetter != null && !_anySetter.hasValueDeserializer()) {
            _anySetter = _anySetter.withValueDeserializer(findDeserializer(ctxt,
                    _anySetter.getType(), _anySetter.getProperty()));
        }

        // as well as delegate-based constructor:
        if (_valueInstantiator.canCreateUsingDelegate()) {
            JavaType delegateType = _valueInstantiator.getDelegateType(ctxt.getConfig());
            if (delegateType == null) {
                throw new IllegalArgumentException("Invalid delegate-creator definition for "+_beanType
                        +": value instantiator ("+_valueInstantiator.getClass().getName()
                        +") returned true for 'canCreateUsingDelegate()', but null for 'getDelegateType()'");
            }
            AnnotatedWithParams delegateCreator = _valueInstantiator.getDelegateCreator();
            // Need to create a temporary property to allow contextual deserializers:
            BeanProperty.Std property = new BeanProperty.Std(null,
                    delegateType, _classAnnotations, delegateCreator);
            _delegateDeserializer = findDeserializer(ctxt, delegateType, property);
        }
        
        if (extTypes != null) {
            _externalTypeIdHandler = extTypes.build();
            // we consider this non-standard, to offline handling
            _nonStandardCreation = true;
        }
        
        _unwrappedPropertyHandler = unwrapped;
        if (unwrapped != null) { // we consider this non-standard, to offline handling
            _nonStandardCreation = true;
        }

        // may need to disable vanilla processing, if unwrapped handling was enabled...
        _vanillaProcessing = _vanillaProcessing && !_nonStandardCreation;
    }

    /**
     * Although most of post-processing is done in resolve(), we only get
     * access to referring property's annotations here; and this is needed
     * to support per-property ObjectIds.
     * We will also consider Shape transformations (read from Array) at this
     * point, since it may come from either Class definition or property.
     */
//  @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt,
            BeanProperty property) throws JsonMappingException
    {
        ObjectIdReader oir = _objectIdReader;
        String[] ignorals = null;

        // First: may have an override for Object Id:
        final AnnotationIntrospector intr = ctxt.getAnnotationIntrospector();
        final AnnotatedMember accessor = (property == null || intr == null)
                ? null : property.getMember();
        if (property != null && intr != null) {
            ignorals = intr.findPropertiesToIgnore(accessor);
            final ObjectIdInfo objectIdInfo = intr.findObjectIdInfo(accessor);
            if (objectIdInfo != null) { // some code duplication here as well (from BeanDeserializerFactory)
                Class<?> implClass = objectIdInfo.getGeneratorType();
                // Property-based generator is trickier
                JavaType idType;
                SettableBeanProperty idProp;
                ObjectIdGenerator<?> idGen;
                if (implClass == ObjectIdGenerators.PropertyGenerator.class) {
                    String propName = objectIdInfo.getPropertyName();
                    idProp = findProperty(propName);
                    if (idProp == null) {
                        throw new IllegalArgumentException("Invalid Object Id definition for "
                                +getBeanClass().getName()+": can not find property with name '"+propName+"'");
                    }
                    idType = idProp.getType();
                    idGen = new PropertyBasedObjectIdGenerator(objectIdInfo.getScope());
                } else { // other types need to be simpler
                    JavaType type = ctxt.constructType(implClass);
                    idType = ctxt.getTypeFactory().findTypeParameters(type, ObjectIdGenerator.class)[0];
                    idProp = null;
                    idGen = ctxt.objectIdGeneratorInstance(accessor, objectIdInfo);
                }
                JsonDeserializer<?> deser = ctxt.findRootValueDeserializer(idType);
                oir = ObjectIdReader.construct(idType, objectIdInfo.getPropertyName(),
                		idGen, deser, idProp);
            }
        }
        // either way, need to resolve serializer:
        BeanDeserializerBase contextual = this;
        if (oir != null && oir != _objectIdReader) {
            contextual = contextual.withObjectIdReader(oir);
        }
        // And possibly add more properties to ignore
        if (ignorals != null && ignorals.length != 0) {
            HashSet<String> newIgnored = ArrayBuilders.setAndArray(contextual._ignorableProps, ignorals);
            contextual = contextual.withIgnorableProperties(newIgnored);
        }

        // One more thing: are we asked to serialize POJO as array?
        JsonFormat.Shape shape = null;
        if (accessor != null) {
            JsonFormat.Value format = intr.findFormat((Annotated) accessor);

            if (format != null) {
                shape = format.getShape();
            }
        }
        if (shape == null) {
            shape = _serializationShape;
        }
        if (shape == JsonFormat.Shape.ARRAY) {
            contextual = contextual.asArrayDeserializer();
        }
        return contextual;
    }

    
    /**
     * Helper method called to see if given property is part of 'managed' property
     * pair (managed + back reference), and if so, handle resolution details.
     */
    protected SettableBeanProperty _resolveManagedReferenceProperty(DeserializationContext ctxt,
            SettableBeanProperty prop)
    {
        String refName = prop.getManagedReferenceName();
        if (refName == null) {
            return prop;
        }
        JsonDeserializer<?> valueDeser = prop.getValueDeserializer();
        SettableBeanProperty backProp = null;
        boolean isContainer = false;
        if (valueDeser instanceof BeanDeserializerBase) {
            backProp = ((BeanDeserializerBase) valueDeser).findBackReference(refName);
        } else if (valueDeser instanceof ContainerDeserializerBase<?>) {
            JsonDeserializer<?> contentDeser = ((ContainerDeserializerBase<?>) valueDeser).getContentDeserializer();
            if (!(contentDeser instanceof BeanDeserializerBase)) {
                String deserName = (contentDeser == null) ? "NULL" : contentDeser.getClass().getName();
                throw new IllegalArgumentException("Can not handle managed/back reference '"+refName
                        +"': value deserializer is of type ContainerDeserializerBase, but content type is not handled by a BeanDeserializer "
                        +" (instead it's of type "+deserName+")");
            }
            backProp = ((BeanDeserializerBase) contentDeser).findBackReference(refName);
            isContainer = true;
        } else if (valueDeser instanceof AbstractDeserializer) {
            backProp = ((AbstractDeserializer) valueDeser).findBackReference(refName);
        } else {
            throw new IllegalArgumentException("Can not handle managed/back reference '"+refName
                    +"': type for value deserializer is not BeanDeserializer or ContainerDeserializerBase, but "
                    +valueDeser.getClass().getName());
        }
        if (backProp == null) {
            throw new IllegalArgumentException("Can not handle managed/back reference '"+refName+"': no back reference property found from type "
                    +prop.getType());
        }
        // also: verify that type is compatible
        JavaType referredType = _beanType;
        JavaType backRefType = backProp.getType();
        if (!backRefType.getRawClass().isAssignableFrom(referredType.getRawClass())) {
            throw new IllegalArgumentException("Can not handle managed/back reference '"+refName+"': back reference type ("
                    +backRefType.getRawClass().getName()+") not compatible with managed type ("
                    +referredType.getRawClass().getName()+")");
        }
        return new ManagedReferenceProperty(prop, refName, backProp,
                _classAnnotations, isContainer);
    }

    /**
     * Helper method called to see if given property might be so-called unwrapped
     * property: these require special handling.
     */
    protected SettableBeanProperty _resolveUnwrappedProperty(DeserializationContext ctxt,
            SettableBeanProperty prop)
    {
        AnnotatedMember am = prop.getMember();
        if (am != null) {
            NameTransformer unwrapper = ctxt.getAnnotationIntrospector().findUnwrappingNameTransformer(am);
            if (unwrapper != null) {
                JsonDeserializer<Object> orig = prop.getValueDeserializer();
                JsonDeserializer<Object> unwrapping = orig.unwrappingDeserializer(unwrapper);
                if (unwrapping != orig && unwrapping != null) {
                    // might be cleaner to create new instance; but difficult to do reliably, so:
                    return prop.withValueDeserializer(unwrapping);
                }
            }
        }
        return null;
    }
    
    /**
     * Helper method that will handle gruesome details of dealing with properties
     * that have non-static inner class as value...
     */
    protected SettableBeanProperty _resolveInnerClassValuedProperty(DeserializationContext ctxt,
            SettableBeanProperty prop)
    {            
        /* Should we encounter a property that has non-static inner-class
         * as value, we need to add some more magic to find the "hidden" constructor...
         */
        JsonDeserializer<Object> deser = prop.getValueDeserializer();
        // ideally wouldn't rely on it being BeanDeserializerBase; but for now it'll have to do
        if (deser instanceof BeanDeserializerBase) {
            BeanDeserializerBase bd = (BeanDeserializerBase) deser;
            ValueInstantiator vi = bd.getValueInstantiator();
            if (!vi.canCreateUsingDefault()) { // no default constructor
                Class<?> valueClass = prop.getType().getRawClass();
                Class<?> enclosing = ClassUtil.getOuterClass(valueClass);
                // and is inner class of the bean class...
                if (enclosing != null && enclosing == _beanType.getRawClass()) {
                    for (Constructor<?> ctor : valueClass.getConstructors()) {
                        Class<?>[] paramTypes = ctor.getParameterTypes();
                        if (paramTypes.length == 1 && paramTypes[0] == enclosing) {
                            if (ctxt.getConfig().canOverrideAccessModifiers()) {
                                ClassUtil.checkAndFixAccess(ctor);
                            }
                            return new InnerClassProperty(prop, ctor);
                        }
                    }
                }
            }
        }
        return prop;
    }

    /*
    /**********************************************************
    /* Public accessors
    /**********************************************************
     */

    @Override
    public boolean isCachable() { return true; }

    /**
     * Overridden to return true for those instances that are
     * handling value for which Object Identity handling is enabled
     * (either via value type or referring property).
     */
    @Override
    public ObjectIdReader getObjectIdReader() {
        return _objectIdReader;
    }
    
    public boolean hasProperty(String propertyName) {
        return _beanProperties.find(propertyName) != null;
    }

    public boolean hasViews() {
        return _needViewProcesing;
    }
    
    /**
     * Accessor for checking number of deserialized properties.
     */
    public int getPropertyCount() { 
        return _beanProperties.size();
    }

    @Override
    public Collection<Object> getKnownPropertyNames() {
        ArrayList<Object> names = new ArrayList<Object>();
        for (SettableBeanProperty prop : _beanProperties) {
            names.add(prop.getName());
        }
        return names;
    }
    
    public final Class<?> getBeanClass() { return _beanType.getRawClass(); }

    @Override public JavaType getValueType() { return _beanType; }

    /**
     * Accessor for iterating over properties this deserializer uses; with
     * the exception that properties passed via Creator methods
     * (specifically, "property-based constructor") are not included,
     * but can be accessed separate by calling
     * {@link #creatorProperties}
     */
    public Iterator<SettableBeanProperty> properties()
    {
        if (_beanProperties == null) {
            throw new IllegalStateException("Can only call after BeanDeserializer has been resolved");
        }
        return _beanProperties.iterator();
    }

    /**
     * Accessor for finding properties that represents values to pass
     * through property-based creator method (constructor or
     * factory method)
     * 
     * @since 2.0
     */
    public Iterator<SettableBeanProperty> creatorProperties()
    {
        if (_propertyBasedCreator == null) {
            return Collections.<SettableBeanProperty>emptyList().iterator();
        }
        return _propertyBasedCreator.properties().iterator();
    }

    /**
     * Accessor for finding the property with given name, if POJO
     * has one. Name used is the external name, i.e. name used
     * in external data representation (JSON).
     * 
     * @since 2.0
     */
    public SettableBeanProperty findProperty(String propertyName)
    {
        SettableBeanProperty prop = (_beanProperties == null) ?
                null : _beanProperties.find(propertyName);
        if (prop == null && _propertyBasedCreator != null) {
            prop = _propertyBasedCreator.findCreatorProperty(propertyName);
        }
        return prop;
    }
    
    /**
     * Method needed by {@link BeanDeserializerFactory} to properly link
     * managed- and back-reference pairs.
     */
    public SettableBeanProperty findBackReference(String logicalName)
    {
        if (_backRefs == null) {
            return null;
        }
        return _backRefs.get(logicalName);
    }

    public ValueInstantiator getValueInstantiator() {
        return _valueInstantiator;
    }
    
    /*
    /**********************************************************
    /* Partial deserializer implementation
    /**********************************************************
     */
    
    @Override
    public final Object deserializeWithType(JsonParser jp, DeserializationContext ctxt,
            TypeDeserializer typeDeserializer)
        throws IOException, JsonProcessingException
    {
        /* 16-Feb-2012, tatu: ObjectId may be used as well... need to check
         *    that first
         */
        if (_objectIdReader != null) {
            JsonToken t = jp.getCurrentToken();
            // should be good enough check; we only care about Strings, integral numbers:
            if (t != null && t.isScalarValue()) {
                return deserializeFromObjectId(jp, ctxt);
            }
        }
        // In future could check current token... for now this should be enough:
        return typeDeserializer.deserializeTypedFromObject(jp, ctxt);
    }

    /**
     * Method called in cases where it looks like we got an Object Id
     * to parse and use as a reference.
     */
    protected Object deserializeFromObjectId(JsonParser jp, DeserializationContext ctxt)
        throws IOException, JsonProcessingException
    {
        Object id = _objectIdReader.deserializer.deserialize(jp, ctxt);
        ReadableObjectId roid = ctxt.findObjectId(id, _objectIdReader.generator);
        // do we have it resolved?
        Object pojo = roid.item;
        if (pojo == null) { // not yet; should wait...
            throw new IllegalStateException("Could not resolve Object Id ["+id+"] -- unresolved forward-reference?");
        }
        return pojo;
    }
    
    /*
    /**********************************************************
    /* Overridable helper methods
    /**********************************************************
     */

    protected void injectValues(DeserializationContext ctxt, Object bean)
            throws IOException, JsonProcessingException
    {
        for (ValueInjector injector : _injectables) {
            injector.inject(ctxt, bean);
        }
    }
    
    /**
     * Method called when a JSON property is encountered that has not matching
     * setter, any-setter or field, and thus can not be assigned.
     */
    @Override
    protected void handleUnknownProperty(JsonParser jp, DeserializationContext ctxt,
            Object beanOrClass, String propName)
        throws IOException, JsonProcessingException
    {
        /* 22-Aug-2010, tatu: Caller now mostly checks for ignorable properties, so
         *    following should not be necessary. However, "handleUnknownProperties()" seems
         *    to still possibly need it so it is left for now.
         */
        // If registered as ignorable, skip
        if (_ignoreAllUnknown ||
            (_ignorableProps != null && _ignorableProps.contains(propName))) {
            jp.skipChildren();
            return;
        }
        /* Otherwise use default handling (call handler(s); if not
         * handled, throw exception or skip depending on settings)
         */
        super.handleUnknownProperty(jp, ctxt, beanOrClass, propName);
    }

    /**
     * Method called to handle set of one or more unknown properties,
     * stored in their entirety in given {@link TokenBuffer}
     * (as field entries, name and value).
     */
    protected Object handleUnknownProperties(DeserializationContext ctxt, Object bean, TokenBuffer unknownTokens)
        throws IOException, JsonProcessingException
    {
        // First: add closing END_OBJECT as marker
        unknownTokens.writeEndObject();
        
        // note: buffer does NOT have starting START_OBJECT
        JsonParser bufferParser = unknownTokens.asParser();
        while (bufferParser.nextToken() != JsonToken.END_OBJECT) {
            String propName = bufferParser.getCurrentName();
            // Unknown: let's call handler method
            bufferParser.nextToken();
            handleUnknownProperty(bufferParser, ctxt, bean, propName);
        }
        return bean;
    }
    
    /**
     * Helper method called to (try to) locate deserializer for given sub-type of
     * type that this deserializer handles.
     */
    protected JsonDeserializer<Object> _findSubclassDeserializer(DeserializationContext ctxt,
            Object bean, TokenBuffer unknownTokens)
        throws IOException, JsonProcessingException
    {  
        JsonDeserializer<Object> subDeser;

        // First: maybe we have already created sub-type deserializer?
        synchronized (this) {
            subDeser = (_subDeserializers == null) ? null : _subDeserializers.get(new ClassKey(bean.getClass()));
        }
        if (subDeser != null) {
            return subDeser;
        }
        // If not, maybe we can locate one. First, need provider
        JavaType type = ctxt.constructType(bean.getClass());
        /* 30-Jan-2012, tatu: Ideally we would be passing referring
         *   property; which in theory we could keep track of via
         *   ResolvableDeserializer (if we absolutely must...).
         *   But for now, let's not bother.
         */
//        subDeser = ctxt.findValueDeserializer(type, _property);
        subDeser = ctxt.findRootValueDeserializer(type);
        // Also, need to cache it
        if (subDeser != null) {
            synchronized (this) {
                if (_subDeserializers == null) {
                    _subDeserializers = new HashMap<ClassKey,JsonDeserializer<Object>>();;
                }
                _subDeserializers.put(new ClassKey(bean.getClass()), subDeser);
            }            
        }
        return subDeser;
    }
    
    /*
    /**********************************************************
    /* Helper methods for error reporting
    /**********************************************************
     */

    /**
     * Method that will modify caught exception (passed in as argument)
     * as necessary to include reference information, and to ensure it
     * is a subtype of {@link IOException}, or an unchecked exception.
     *<p>
     * Rules for wrapping and unwrapping are bit complicated; essentially:
     *<ul>
     * <li>Errors are to be passed as is (if uncovered via unwrapping)
     * <li>"Plain" IOExceptions (ones that are not of type
     *   {@link JsonMappingException} are to be passed as is
     *</ul>
     */
    public void wrapAndThrow(Throwable t, Object bean, String fieldName,
            DeserializationContext ctxt)
        throws IOException
    {
        /* 05-Mar-2009, tatu: But one nasty edge is when we get
         *   StackOverflow: usually due to infinite loop. But that
         *   usually gets hidden within an InvocationTargetException...
         */
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        // Errors and "plain" IOExceptions to be passed as is
        if (t instanceof Error) {
            throw (Error) t;
        }
        boolean wrap = (ctxt == null) || ctxt.isEnabled(DeserializationFeature.WRAP_EXCEPTIONS);
        // Ditto for IOExceptions; except we may want to wrap mapping exceptions
        if (t instanceof IOException) {
            if (!wrap || !(t instanceof JsonMappingException)) {
                throw (IOException) t;
            }
        } else if (!wrap) { // [JACKSON-407] -- allow disabling wrapping for unchecked exceptions
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
        }
        // [JACKSON-55] Need to add reference information
        throw JsonMappingException.wrapWithPath(t, bean, fieldName);
    }

    public void wrapAndThrow(Throwable t, Object bean, int index, DeserializationContext ctxt)
        throws IOException
    {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        // Errors and "plain" IOExceptions to be passed as is
        if (t instanceof Error) {
            throw (Error) t;
        }
        boolean wrap = (ctxt == null) || ctxt.isEnabled(DeserializationFeature.WRAP_EXCEPTIONS);
        // Ditto for IOExceptions; except we may want to wrap mapping exceptions
        if (t instanceof IOException) {
            if (!wrap || !(t instanceof JsonMappingException)) {
                throw (IOException) t;
            }
        } else if (!wrap) { // [JACKSON-407] -- allow disabling wrapping for unchecked exceptions
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
        }
        // [JACKSON-55] Need to add reference information
        throw JsonMappingException.wrapWithPath(t, bean, index);
    }

    protected void wrapInstantiationProblem(Throwable t, DeserializationContext ctxt)
        throws IOException
    {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        // Errors and "plain" IOExceptions to be passed as is
        if (t instanceof Error) {
            throw (Error) t;
        }
        boolean wrap = (ctxt == null) || ctxt.isEnabled(DeserializationFeature.WRAP_EXCEPTIONS);
        if (t instanceof IOException) {
            // Since we have no more information to add, let's not actually wrap..
            throw (IOException) t;
        } else if (!wrap) { // [JACKSON-407] -- allow disabling wrapping for unchecked exceptions
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
        }
        throw ctxt.instantiationException(_beanType.getRawClass(), t);
    }
}
