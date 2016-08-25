package com.a5000.platform.api.annotations.generators;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Copyright 2016 Cyril A. Karpenko <self@nikelin.ru>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * In a contrast to {@link ConventionalQuery} logic of all queries mapped by this
 * annotation declares only by its {@link NativeQuery#value()} field value and not by
 * it's name.
 *
 * For example next annotation instance which belongs to the some class Example:
 * @example
 * {@code
 *  @NativeQuery( value = "select p from Example p where p.name = :name", name = "findByName",
 *      parameters = {
 *          @Parameter( value = "name", value = String.class )
 *      }
 *  )
 * }
 *
 * would be transformed into the method in the some ExampleRepository like next:
 * {@code
 *      public interface ExampleRepository extends JpaRepository<Example, Long> {
 *
 *          @Query("select p from Example p where p.name = :name")
 *          public Collection<Example> findByName( @Param("name") String name );
 *
 *      }
 * }
 *
 * More about Spring Data conventions on repository methods naming you can read
 * by this link: {@linkplain http://static.springsource.org/spring-data/commons/docs/current/reference/html/}
 *
 */
@Target(ElementType.TYPE)
public @interface NativeQuery {

    /**
     * Native JPQL query string.
     *
     * @return
     */
    public String value();

    /**
     * Name of method which would be added to resulting Spring Repository
     * @return
     */
    public String name();

    /**
     * Detect is this native query requiring new transaction open to be correctly executed
     * @return
     */
    public boolean isTransactional() default false;

    /**
     * Type of execution result; if {@link NativeQuery#isCollection()} has been set as true,
     * the {@link NativeQuery#resultType()} would be used as a generic parameter value under a
     * list type.
     *
     * @return
     */
    public Class<?> resultType() default Object.class;

    public Parameter[] parameters() default {};

    /**
     * If turned on, also additional method adds to resulting repository,
     * which return as a result {@link com.a5000.platform.api.annotations.paging.PageResponse<T>} where
     * T would be initialized with a value of {@link ConventionalQuery#resultType()}
     * @return
     */
    public boolean isPageable() default false;

    /**
     * Based on value of this field generator decide which result type to choose. If the value is {@literal false},
     * then plain {@link ConventionalQuery#resultType()} would be used; otherwise {@link java.util.List} with a
     * {@link ConventionalQuery#resultType()} as a generic parameter.
     * @return
     */
    public boolean isCollection() default true;

    /**
     * If is {@literal true}, then resulting method would be mapped by
     * {@link org.springframework.data.jpa.repository.Modify} annotation.
     *
     * @return
     */
    public boolean isModifying() default false;

}
