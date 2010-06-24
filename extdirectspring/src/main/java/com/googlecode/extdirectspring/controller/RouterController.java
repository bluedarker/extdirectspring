/**
 * Copyright 2010 Ralph Schaer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.extdirectspring.controller;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.support.ConversionServiceFactory;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ValueConstants;
import com.googlecode.extdirectspring.annotation.ExtDirectMethod;
import com.googlecode.extdirectspring.annotation.ExtDirectPollMethod;
import com.googlecode.extdirectspring.annotation.ExtDirectStoreModifyMethod;
import com.googlecode.extdirectspring.annotation.ExtDirectStoreReadMethod;
import com.googlecode.extdirectspring.bean.ExtDirectFormLoadResult;
import com.googlecode.extdirectspring.bean.ExtDirectPollResponse;
import com.googlecode.extdirectspring.bean.ExtDirectRequest;
import com.googlecode.extdirectspring.bean.ExtDirectResponse;
import com.googlecode.extdirectspring.bean.ExtDirectStoreReadRequest;
import com.googlecode.extdirectspring.bean.ExtDirectStoreResponse;
import com.googlecode.extdirectspring.util.ExtDirectSpringUtil;
import com.googlecode.extdirectspring.util.SupportedParameters;

/**
*
* @author mansari
* @author Ralph Schaer
*/
@Controller
public class RouterController implements ApplicationContextAware {

  private static final GenericConversionService genericConversionService = ConversionServiceFactory.createDefaultConversionService();
  
  private static final Logger log = LoggerFactory.getLogger(RouterController.class);

  private ApplicationContext context;

  @Override
  public void setApplicationContext(ApplicationContext context) throws BeansException {
    this.context = context;
  }

  @RequestMapping(value = "/poll/{beanName}/{method}/{event}")
  @ResponseBody
  public ExtDirectPollResponse poll(@PathVariable String beanName, @PathVariable String method, @PathVariable String event,
      HttpServletRequest request, HttpServletResponse response, Locale locale) throws Exception {
    ExtDirectPollResponse directPollResponse = new ExtDirectPollResponse();
    directPollResponse.setName(event);

    Method beanMethod = ExtDirectSpringUtil.findMethod(context, beanName, method);
    
    if (AopUtils.isAopProxy(context.getBean(beanName))) {
      beanMethod = ExtDirectSpringUtil.findMethodWithAnnotation(beanMethod, ExtDirectPollMethod.class);
    }
    
    
    if (beanMethod != null) {

      Annotation[][] parameterAnnotations = beanMethod.getParameterAnnotations();

      Class<?>[] parameterTypes = beanMethod.getParameterTypes();
      Object[] parameters = null;
      if (parameterTypes.length > 0) {
        parameters = new Object[parameterTypes.length];
        int paramIndex = 0;
        for (Class<?> parameterType : parameterTypes) {

          if (SupportedParameters.SERVLET_RESPONSE.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = response;
          } else if (SupportedParameters.SERVLET_REQUEST.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = request;
          } else if (SupportedParameters.SESSION.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = request.getSession();
          } else if (SupportedParameters.LOCALE.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = locale;
          } else {
            parameters[paramIndex] = handleRequestParam(request, null, parameterAnnotations[paramIndex], parameterType);
          }

          paramIndex++;
        }
      } 

      directPollResponse.setData(ExtDirectSpringUtil.invoke(context, beanName, method, parameters));
      return directPollResponse;

    }

    throw new IllegalArgumentException("Method '" + beanName + "." + method + "' not found");

  }

  
  @RequestMapping(value = "/router", method = RequestMethod.POST, params = "extAction")
  public String router(@RequestParam(value = "extAction") String extAction, 
                       @RequestParam(value = "extMethod") String extMethod) {

    Method method = ExtDirectSpringUtil.findMethod(context, extAction, extMethod);
    if (method != null) {
      RequestMapping annotation = AnnotationUtils.findAnnotation(method, RequestMapping.class);
      if (annotation != null && StringUtils.hasText(annotation.value()[0])) {

        String forwardPath = annotation.value()[0];
        if (forwardPath.charAt(0) == '/' && forwardPath.length() > 1) {
          forwardPath = forwardPath.substring(1, forwardPath.length());
        }

        return "forward:" + forwardPath;
      }
      throw new IllegalArgumentException("Invalid remoting form method: " + extAction + "." + extMethod);
    }

    return null;

  }

  @RequestMapping(value = "/router", method = RequestMethod.POST, params = "!extAction")
  @ResponseBody
  public List<ExtDirectResponse> router(HttpServletRequest request, HttpServletResponse response, Locale locale,
      @RequestBody String rawRequestString) {

    List<ExtDirectRequest> directRequests = getExtDirectRequests(rawRequestString);
    List<ExtDirectResponse> directResponses = new ArrayList<ExtDirectResponse>();

    for (ExtDirectRequest directRequest : directRequests) {

      ExtDirectResponse directResponse = new ExtDirectResponse();
      directResponse.setAction(directRequest.getAction());
      directResponse.setMethod(directRequest.getMethod());
      directResponse.setTid(directRequest.getTid());
      directResponse.setType(directRequest.getType());

      try {
        Object result = processRemotingRequest(request, response, locale, directRequest);

        if (result != null) {
          Method method = ExtDirectSpringUtil.findMethod(context, directRequest.getAction(), directRequest.getMethod());
          
          if (isFormLoadMethod(method)) {
            if (!ExtDirectFormLoadResult.class.isAssignableFrom(result.getClass())) {
              result = new ExtDirectFormLoadResult(result);
            }
          } else if (getDirectStoreType(method) != null) {            
            if (!ExtDirectStoreResponse.class.isAssignableFrom(result.getClass())) {
              result = new ExtDirectStoreResponse((Collection)result);
            }
          }
        }

        directResponse.setResult(result);
      } catch (Exception e) {
        log.error("Error on method: " + directRequest.getMethod(), e);

        directResponse.setSuccess(false);
        if (log.isDebugEnabled()) {
          directResponse.setType("exception");
          directResponse.setMessage(e.getMessage());
          directResponse.setWhere(e.getMessage());
        } else {
          directResponse.setMessage("server error");
        }
      }

      directResponses.add(directResponse);
    }

    return directResponses;

  }

  private final boolean isFormLoadMethod(Method method) {    
    ExtDirectMethod annotation = AnnotationUtils.findAnnotation(method, ExtDirectMethod.class);
    return annotation != null && annotation.formLoad();
  }

  private final boolean isDirectStoreReadMethod(Method method) {    
    return AnnotationUtils.findAnnotation(method, ExtDirectStoreReadMethod.class) != null;
  }

  
  private final Class<?> getDirectStoreType(Method method) {
    ExtDirectStoreModifyMethod annotation = AnnotationUtils.findAnnotation(method, ExtDirectStoreModifyMethod.class);
    if (annotation != null) {
      return  annotation.type();
    }
    return null;
  }
  

  private final Object processRemotingRequest(HttpServletRequest request, HttpServletResponse response, Locale locale,
      ExtDirectRequest directRequest) throws Exception {

    Method method = ExtDirectSpringUtil.findMethod(context, directRequest.getAction(), directRequest.getMethod());
    if (method != null) {
      
      
      int jsonParamIndex = 0;
            
      ExtDirectStoreReadRequest directStoreReadRequest = null;
      
      List<Object> directStoreModifyRecords = null;
      Class<?> directStoreModifyType = null;
      
      Annotation[][] parameterAnnotations = null; 
      Map<String,Object> remainingParameters = null;
      
      if (isDirectStoreReadMethod(method)) {
        Method beanMethod = ExtDirectSpringUtil.findMethodWithAnnotation(method, ExtDirectStoreReadMethod.class);
        parameterAnnotations = beanMethod.getParameterAnnotations();
        if (directRequest.getData() != null && directRequest.getData().length > 0) {
          directStoreReadRequest = new ExtDirectStoreReadRequest();
          remainingParameters = fillObjectFromMap(directStoreReadRequest, (Map)directRequest.getData()[0]);
          jsonParamIndex = 1;
        }
      } else if ((directStoreModifyType = getDirectStoreType(method)) != null) {
        Method beanMethod = ExtDirectSpringUtil.findMethodWithAnnotation(method, ExtDirectStoreModifyMethod.class);
        parameterAnnotations = beanMethod.getParameterAnnotations();
        
        
        if (directRequest.getData() != null && directRequest.getData().length > 0) {
          Map<String,Object> jsonData = (LinkedHashMap<String,Object>)directRequest.getData()[0];
          
          ArrayList<Object> records = (ArrayList<Object>)jsonData.get("records");
          directStoreModifyRecords = convertObjectEntriesToType(records, directStoreModifyType);
          jsonParamIndex = 1;
          
          remainingParameters = new HashMap<String,Object>();
          for (String parameterName : jsonData.keySet()) {
            if (!"records".equals(parameterName)) {
              remainingParameters.put(parameterName, jsonData.get(parameterName));
            }
          }

        }
        
      }
      
      
      Class<?>[] parameterTypes = method.getParameterTypes();
      Object[] parameters = null;
      
      if (parameterTypes.length > 0) {
        parameters = new Object[parameterTypes.length];
        int paramIndex = 0;        
        for (Class<?> parameterType : parameterTypes) {

          if (SupportedParameters.SERVLET_RESPONSE.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = response;
          } else if (SupportedParameters.SERVLET_REQUEST.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = request;
          } else if (SupportedParameters.SESSION.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = request.getSession();
          } else if (SupportedParameters.LOCALE.getSupportedClass().isAssignableFrom(parameterType)) {
            parameters[paramIndex] = locale;
          } else if (directStoreReadRequest != null && ExtDirectStoreReadRequest.class.isAssignableFrom(parameterType)) {
            parameters[paramIndex] = directStoreReadRequest;
          } else if (remainingParameters != null && (directStoreReadRequest != null || directStoreModifyRecords != null) && 
              ExtDirectSpringUtil.containsAnnotation(parameterAnnotations[paramIndex], RequestParam.class)) {
            parameters[paramIndex] = handleRequestParam(null, remainingParameters, parameterAnnotations[paramIndex], parameterType);
          } else if (directStoreModifyRecords != null && parameterType.isAssignableFrom(directStoreModifyRecords.getClass())) {
            parameters[paramIndex] = directStoreModifyRecords;          
          } else if (directRequest.getData() != null && directRequest.getData().length > jsonParamIndex) {
            
            Object jsonParam = directRequest.getData()[jsonParamIndex];
            
            if (parameterType.getClass().equals(String.class)) {            
              parameters[paramIndex] = ExtDirectSpringUtil.serializeObjectToJson(jsonParam);
            } else if (parameterType.isPrimitive()) {
              parameters[paramIndex] = jsonParam;
            } else {
              parameters[paramIndex] = ExtDirectSpringUtil.deserializeJsonToObject(ExtDirectSpringUtil.serializeObjectToJson(jsonParam), parameterType);
            }

            jsonParamIndex++;
          } else {
            throw new IllegalArgumentException(
                "Error, param mismatch. Please check your remoting method signature to ensure all supported param types are used.");
          }

          paramIndex++;
        }
      }

      return ExtDirectSpringUtil.invoke(context, directRequest.getAction(), directRequest.getMethod(), parameters);
    }
   
    return null;
  }
  
  private Object handleRequestParam(HttpServletRequest request, Map<String,Object> valueContainer, Annotation[] parameterAnnotations, Class<?> parameterType) {
    boolean required = false;
    String parameterName = null;
    String defaultValue = null;
    for (Annotation paramAnn : parameterAnnotations) {
      if (RequestParam.class.isInstance(paramAnn)) {
        RequestParam requestParam = (RequestParam)paramAnn;
        parameterName = requestParam.value();
        required = requestParam.required();
        defaultValue = requestParam.defaultValue();
        defaultValue = (ValueConstants.DEFAULT_NONE.equals(defaultValue) ? null : defaultValue);
      }
    }

    if (parameterName != null) { 
      Object value;
      if (request != null) {
        value = request.getParameter(parameterName);
      } else {
        value = valueContainer.get(parameterName);
      }
      if (value == null) {
        value = defaultValue;
      }

      if (value != null) {
        return genericConversionService.convert(value, parameterType);
      } 
      if (required) {
        throw new IllegalArgumentException("Missing request parameter: " + parameterName);
      }
    }
    
    return null;
  }
  
  private Map<String,Object> fillObjectFromMap(Object to, Map<String,Object> from) {
    Set<String> foundParameters = new HashSet<String>();
    
    for (String parameterName : from.keySet()) {
      PropertyDescriptor descriptor = BeanUtils.getPropertyDescriptor(to.getClass(), parameterName);
      if (descriptor != null && descriptor.getWriteMethod() != null) {        
        try {
          descriptor.getWriteMethod().invoke(to, genericConversionService.convert(from.get(parameterName), descriptor.getPropertyType()));
          foundParameters.add(parameterName);
        } catch (IllegalArgumentException e) {
          log.error("fillObjectFromMap", e);
        } catch (IllegalAccessException e) {
          log.error("fillObjectFromMap", e);
        } catch (InvocationTargetException e) {
          log.error("fillObjectFromMap", e);
        }
      }
    }
    
    Map<String,Object> remainingParameters = new HashMap<String,Object>();
    for (String parameterName : from.keySet()) {
      if (!foundParameters.contains(parameterName)) {
        remainingParameters.put(parameterName, from.get(parameterName));
      }
    }
    return remainingParameters;
  }

  private List<Object> convertObjectEntriesToType(ArrayList<Object> records, Class< ? > directStoreType) {
    if (records != null) {
      List<Object> convertedList = new ArrayList<Object>();
      for (Object record : records) {
        Object convertedObject = ExtDirectSpringUtil.deserializeJsonToObject(ExtDirectSpringUtil.serializeObjectToJson(record), directStoreType);
        convertedList.add(convertedObject);
      }
      return convertedList;
    }
    return null;
  }

  private List<ExtDirectRequest> getExtDirectRequests(String rawRequestString) {
    List<ExtDirectRequest> directRequests = new ArrayList<ExtDirectRequest>();

    if (rawRequestString.length() > 0 && rawRequestString.charAt(0) == '[') {
      directRequests.addAll(ExtDirectSpringUtil.deserializeJsonToObject(rawRequestString, new TypeReference<List<ExtDirectRequest>>() {/*empty*/}));
    } else {
      ExtDirectRequest directRequest = ExtDirectSpringUtil.deserializeJsonToObject(rawRequestString, ExtDirectRequest.class);
      directRequests.add(directRequest);
    }

    return directRequests;
  }

}
