/*
 * JBoss, Home of Professional Open Source
 * Copyright [2011], Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.seam.spring.extension;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.*;
import javax.enterprise.util.AnnotationLiteral;

import org.jboss.seam.spring.context.SpringContext;
import org.jboss.seam.spring.inject.SpringBean;
import org.jboss.seam.spring.utils.Locations;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;

/**
 * @author: Marius Bogoevici
 */
public class SpringContextBootstrapExtension implements Extension {


    public Map<String, ConfigurableApplicationContext> contextDefinitions = new HashMap<String, ConfigurableApplicationContext>();

    private Set<String> vetoedTypes = new HashSet<String>();

    private WebApplicationContext webContext;

    public void handleSpringExtensionProducers(final @Observes ProcessProducer<?, Object> processProducer, final BeanManager beanManager) {
        if (processProducer.getAnnotatedMember().isAnnotationPresent(SpringBean.class)) {
            processProducer.setProducer(new SpringBeanProducer(processProducer, beanManager));
        } else if (processProducer.getAnnotatedMember().isAnnotationPresent(SpringContext.class)) {
            processProducer.setProducer(new SpringContextProducer(processProducer, processProducer.getProducer()));
        }
    }


    public void loadSpringContext(@Observes BeforeBeanDiscovery beforeBeanDiscovery, BeanManager beanManager) {
        if (isAutoProducingEnabled()) {
            webContext = ContextLoader.getCurrentWebApplicationContext();
            if (webContext != null) {
                System.out.println("Web application context found, skip scanning of config files");
                return;
            }
        }

        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(Locations.SEAM_SPRING_CONTEXTS_LOCATION);
        if (inputStream != null) {
            Properties contextLocations = new Properties();
            try {
                contextLocations.load(inputStream);

                for (String contextName : contextLocations.stringPropertyNames()) {
                    ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(contextLocations.getProperty(contextName));
                    contextDefinitions.put(contextName, context);
                    for (String beanDefinitionName : context.getBeanDefinitionNames()) {
                        vetoedTypes.add(context.getBeanFactory().getBeanDefinition(beanDefinitionName).getBeanClassName());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    public void registerSpringContextBeans(@Observes AfterBeanDiscovery afterBeanDiscovery) {
        for (String contextName : contextDefinitions.keySet()) {
            afterBeanDiscovery.addBean(new SpringContextBean(contextDefinitions.get(contextName), new SpringContextLiteral(contextName)));
        }
    }

    public void autoVeto(@Observes ProcessAnnotatedType<?> processAnnotatedType) {
        String name = processAnnotatedType.getAnnotatedType().getJavaClass().getName();
        if (vetoedTypes.contains(name)) {
            processAnnotatedType.veto();
        }
    }

    void afterBeanDiscovery(@Observes AfterBeanDiscovery abd, BeanManager bm) {
        if (isAutoProducingEnabled()) {
            if (webContext != null) {
                addBeansForContext(abd, bm, webContext);
            } else {
                for (ConfigurableApplicationContext applicationContext : contextDefinitions.values()) {
                    addBeansForContext(abd, bm, applicationContext);
                }
            }
        }
    }

    private void addBeansForContext(AfterBeanDiscovery abd, BeanManager bm, ApplicationContext applicationContext) {
        System.out.println("Processing " + applicationContext.getId());
        String[] beanDefinitionNames = applicationContext.getBeanDefinitionNames();
        for (String beanDefinitionName : beanDefinitionNames) {
            Class<? extends Object> bean = applicationContext.getBean(beanDefinitionName).getClass();
            addBean(applicationContext, bean, abd, bm);
            System.out.println("Adding bean " + bean.getName());
        }
    }

    private boolean isAutoProducingEnabled() {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(Locations.SEAM_SPRING_CONFIG);
        if (inputStream != null) {
            Properties contextLocations = new Properties();
            try {
                contextLocations.load(inputStream);

                String enableAutoProducing = contextLocations.getProperty("enableAutoProducing");
                System.out.println("Auto producing enabled");
                return enableAutoProducing != null && enableAutoProducing.equals("true");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            return false;
        }
    }

    private <T> void addBean(final ApplicationContext applicationContext, final Class<T> type, AfterBeanDiscovery
            abd, BeanManager bm) {
        AnnotatedType<T> at = bm.createAnnotatedType(type);

        final InjectionTarget<T> it = bm.createInjectionTarget(at);
        abd.addBean(new Bean<T>() {
            @Override
            public T create(CreationalContext<T> ctx) {

                T instance = applicationContext.getBean(type);
                it.inject(instance, ctx);
                it.postConstruct(instance);
                return instance;
            }

            @Override
            public Set<Type> getTypes() {
                Set<Type> types = new HashSet<Type>();
                types.add(type);
                types.add(Object.class);
                return types;
            }


            @Override
            public Set<Annotation> getQualifiers() {
                Set<Annotation> qualifiers = new HashSet<Annotation>();

                qualifiers.add(new AnnotationLiteral<Default>() {
                });

                qualifiers.add(new AnnotationLiteral<Any>() {
                });

                return qualifiers;
            }

            @Override
            public Class<? extends Annotation> getScope() {
                return Dependent.class;
            }

            @Override
            public String getName() {
                return type.getName().toLowerCase();
            }

            @Override
            public Set<Class<? extends Annotation>> getStereotypes() {
                return Collections.emptySet();
            }

            @Override
            public Class<?> getBeanClass() {
                return type;
            }

            @Override
            public boolean isAlternative() {
                return false;
            }

            @Override
            public boolean isNullable() {
                return false;
            }

            @Override
            public Set<InjectionPoint> getInjectionPoints() {
                return it.getInjectionPoints();
            }

            @Override
            public void destroy(T instance, CreationalContext<T> ctx) {
                it.preDestroy(instance);

                it.dispose(instance);

                ctx.release();

            }
        });
    }

}
