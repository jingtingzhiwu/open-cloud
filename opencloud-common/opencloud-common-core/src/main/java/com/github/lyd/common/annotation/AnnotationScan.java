package com.github.lyd.common.annotation;

import com.github.lyd.common.constants.MqConstants;
import com.github.lyd.common.utils.StringUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义注解扫描
 *
 * @author liuyadu
 */
@Slf4j
public class AnnotationScan implements ApplicationListener<ApplicationReadyEvent> {
    private AmqpTemplate amqpTemplate;

    public AnnotationScan(AmqpTemplate amqpTemplate) {
        this.amqpTemplate = amqpTemplate;
    }

    /**
     * 初始化方法
     *
     * @param event
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext applicationContext = event.getApplicationContext();
        Map<String, Object> resourceServer = applicationContext.getBeansWithAnnotation(EnableResourceServer.class);
        if (resourceServer == null || resourceServer.isEmpty()) {
            // 只扫描资源服务器
            return;
        }
        amqpTemplate = applicationContext.getBean(RabbitTemplate.class);
        Environment env = applicationContext.getEnvironment();
        String serviceId = env.getProperty("spring.application.name", "application");
        log.info("ApplicationReadyEvent:{}", serviceId);
        List<Map<String, Object>> list = Lists.newArrayList();
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(RestController.class);
        //遍历Bean
        Set<Map.Entry<String, Object>> entries = beans.entrySet();
        Iterator<Map.Entry<String, Object>> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> map = iterator.next();
            Class<?> aClass = map.getValue().getClass();
            String prefix = "";
            if (aClass.isAnnotationPresent(RequestMapping.class)) {
                prefix = aClass.getAnnotation(RequestMapping.class).value()[0];
            }
            Method[] methods = aClass.getMethods();
            for (Method m : methods) {
                String path = getPath(m);
                if (StringUtils.isBlank(path)) {
                    continue;
                }
                //api 资源
                String code = m.getName();
                String name = "";
                String desc = "";
                if (m.isAnnotationPresent(ApiOperation.class)) {
                    ApiOperation operation = m.getAnnotation(ApiOperation.class);
                    name = operation.value();
                    desc = operation.notes();
                }

                if (StringUtils.isBlank(name)) {
                    name = code;
                }

                if (StringUtils.isBlank(desc)) {
                    desc = name;
                }
                path = prefix + path;
                Map<String, Object> api = Maps.newHashMap();
                api.put("apiCode", code);
                api.put("apiName", name);
                api.put("serviceId", serviceId);
                api.put("path", path);
                api.put("apiDesc", desc);
                list.add(api);
            }
        }
        if (amqpTemplate != null) {
            // 发送mq扫描消息
            amqpTemplate.convertAndSend(MqConstants.QUEUE_SCAN_API_RESOURCE, list);
        }
    }

    private String getPath(Method method) {
        StringBuilder path = new StringBuilder();
        if (method.isAnnotationPresent(GetMapping.class)) {
            path.append(method.getAnnotation(GetMapping.class).value()[0]);
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            path.append(method.getAnnotation(PostMapping.class).value()[0]);
        } else if (method.isAnnotationPresent(RequestMapping.class)) {
            path.append(method.getAnnotation(RequestMapping.class).value()[0]);
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            path.append(method.getAnnotation(PutMapping.class).value()[0]);
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            path.append(method.getAnnotation(DeleteMapping.class).value()[0]);
        }
        return path.toString();
    }
}
