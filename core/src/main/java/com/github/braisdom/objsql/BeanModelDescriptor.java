/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.braisdom.objsql;

import com.github.braisdom.objsql.annotations.Column;
import com.github.braisdom.objsql.annotations.DomainModel;
import com.github.braisdom.objsql.annotations.PrimaryKey;
import com.github.braisdom.objsql.annotations.Transient;
import com.github.braisdom.objsql.reflection.ClassUtils;
import com.github.braisdom.objsql.reflection.PropertyUtils;
import com.github.braisdom.objsql.transition.ColumnTransition;
import com.github.braisdom.objsql.util.Inflector;
import com.github.braisdom.objsql.util.StringUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.JDBCType;
import java.sql.SQLType;
import java.util.*;

/**
 * The default implementation for <code>DomainModelDescriptor</code> with JavaBean
 *
 * @param <T>
 */
public class BeanModelDescriptor<T> implements DomainModelDescriptor<T> {

    private final static List<Class> COLUMNIZABLE_FIELD_TYPES = Arrays.asList(new Class[]{
            String.class, char.class,
            Long.class, long.class,
            Integer.class, int.class,
            Short.class, short.class,
            Float.class, float.class,
            Double.class, double.class,
            BigInteger.class, BigDecimal.class
    });

    private final Class<T> domainModelClass;
    private final Map<String, ColumnTransition> columnTransitionMap;
    private final Map<String, Field> columnToField;
    private final DomainModel domainModel;

    private class DefaultFieldValue implements FieldValue {

        private final SQLType sqlType;
        private Object value;

        public DefaultFieldValue(Object value) {
            this(JDBCType.NULL, value);
        }

        public DefaultFieldValue(SQLType sqlType, Object value) {
            this.sqlType = sqlType;
            this.value = value;
        }

        @Override
        public boolean isNull() {
            return value == null;
        }

        @Override
        public SQLType getSQLType() {
            return sqlType;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public void setValue(Object value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    public BeanModelDescriptor(Class<T> domainModelClass) {
        Objects.requireNonNull(domainModelClass, "The domainModelClass cannot be null");

        DomainModel domainModel = domainModelClass.getAnnotation(DomainModel.class);

        Objects.requireNonNull(domainModel, String.format("%s has no annotation: DomainModel", domainModelClass.getSimpleName()));
        Objects.requireNonNull(Tables.getPrimaryKey(domainModelClass), String.format("The %s has no primary key",
                domainModelClass.getSimpleName()));

        Objects.requireNonNull(domainModel, "The domainModelClass must have DomainModel annotation");

        this.domainModel = domainModel;
        this.domainModelClass = domainModelClass;
        this.columnTransitionMap = new HashMap<>();
        this.columnToField = new HashMap<>();

        prepareColumnToPropertyOverrides(domainModelClass);
        instantiateColumnTransitionMap(domainModelClass.getDeclaredFields());
    }

    @Override
    public String getDataSourceName() {
        if (StringUtil.isBlank(domainModel.dataSource())) {
            return ConnectionFactory.DEFAULT_DATA_SOURCE_NAME;
        } else {
            return domainModel.dataSource();
        }
    }

    @Override
    public T newInstance() {
        return ClassUtils.createNewInstance(domainModelClass);
    }

    @Override
    public void setGeneratedKey(T bean, Object primaryKeyValue) {
        Field primaryField = Tables.getPrimaryField(domainModelClass);
        setFieldValue(bean, primaryField.getName(), primaryKeyValue);
    }

    @Override
    public Class getDomainModelClass() {
        return domainModelClass;
    }

    @Override
    public DomainModelDescriptor getRelatedModeDescriptor(Class relatedClass) {
        return new BeanModelDescriptor(relatedClass);
    }

    @Override
    public String[] getColumns() {
        return Arrays.stream(getColumnizableFields(domainModelClass, true, true))
                .map(field -> getColumnName(field)).toArray(String[]::new);
    }

    @Override
    public String getTableName() {
        return Tables.getTableName(domainModelClass);
    }

    @Override
    public PrimaryKey getPrimaryKey() {
        return Tables.getPrimaryKey(domainModelClass);
    }

    @Override
    public Object getPrimaryValue(Object domainObject) {
        return PropertyUtils.read(domainObject, getPrimaryKey().name());
    }

    @Override
    public boolean skipNullOnUpdate() {
        return domainModelClass.getAnnotation(DomainModel.class).skipNullValueOnUpdating();
    }

    @Override
    public String[] getInsertableColumns() {
        String primaryName = getPrimaryKey().name();
        return Arrays.stream(getColumnizableFields(domainModelClass, true, false))
                .filter(field -> {
                    if (field.getName().equals(primaryName)) {
                        return !domainModel.skipPrimaryValueOnInsert();
                    } else {
                        Column column = field.getAnnotation(Column.class);
                        return column == null ? true : column.insertable();
                    }
                })
                .map(field -> getColumnName(field)).toArray(String[]::new);
    }

    @Override
    public String[] getUpdatableColumns() {
        return Arrays.stream(getColumnizableFields(domainModelClass, false, true))
                .filter(field -> field.getAnnotation(PrimaryKey.class) == null)
                .map(field -> getColumnName(field)).toArray(String[]::new);
    }

    @Override
    public String getFieldName(String fieldName) {
        Field field = columnToField.get(fieldName);
        return field == null ? null : field.getName();
    }

    @Override
    public Optional<String> getFieldDefaultValue(String fieldName) {
        try {
            DomainModel domainModel = domainModelClass.getAnnotation(DomainModel.class);
            Field field = domainModelClass.getDeclaredField(fieldName);

            if (field.getName().equals(domainModel.primaryFieldName())
                    && !StringUtil.isEmpty(domainModel.primaryKeyDefaultValue())) {
                return Optional.of(domainModel.primaryKeyDefaultValue());
            }

            Column column = field.getAnnotation(Column.class);
            if (column != null && !StringUtil.isEmpty(column.defaultValue())) {
                return Optional.of(column.defaultValue());
            }
            return Optional.empty();
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    @Override
    public boolean hasDefaultValue(String fieldName) {
        try {
            DomainModel domainModel = domainModelClass.getAnnotation(DomainModel.class);
            Field field = domainModelClass.getDeclaredField(fieldName);

            if (field.getName().equals(domainModel.primaryFieldName())) {
                return !StringUtil.isEmpty(domainModel.primaryKeyDefaultValue());
            }

            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                return !StringUtil.isEmpty(column.defaultValue());
            }
            return false;
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    @Override
    public FieldValue getFieldValue(Object bean, String fieldName) {
        try {
            Object value = PropertyUtils.read(bean, fieldName);

            DomainModel domainModel = domainModelClass.getAnnotation(DomainModel.class);
            Field field = domainModelClass.getDeclaredField(fieldName);

            if (value == null && domainModel.primaryFieldName().equals(fieldName)) {
                if (!StringUtil.isBlank(domainModel.primaryKeyDefaultValue())) {
                    return new DefaultFieldValue(JDBCType.NUMERIC, domainModel.primaryKeyDefaultValue());
                }
            }

            if (value == null) {
                return new DefaultFieldValue(null);
            }

            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                return new DefaultFieldValue(column.sqlType(), value);
            }

            return new DefaultFieldValue(value);
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    @Override
    public Class getFieldType(String fieldName) {
        try {
            if (fieldName == null) {
                return null;
            }
            Field field = domainModelClass.getDeclaredField(fieldName);
            return field == null ? null : field.getType();
        } catch (NoSuchFieldException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    @Override
    public void setFieldValue(T modelObject, String fieldName, Object fieldValue) {
        PropertyUtils.write(modelObject, fieldName, fieldValue);
    }

    @Override
    public boolean isTransitable(String fieldName) {
        return columnTransitionMap.get(fieldName) != null;
    }

    @Override
    public ColumnTransition getColumnTransition(String fieldName) {
        return columnTransitionMap.get(fieldName);
    }

    protected Field[] getColumnizableFields(Class domainModelClass, boolean insertable, boolean updatable) {
        DomainModel domainModel = (DomainModel) domainModelClass.getAnnotation(DomainModel.class);
        Field primaryField = Tables.getPrimaryField(domainModelClass);
        Field[] fields = domainModelClass.getDeclaredFields();

        if (domainModel.allFieldsPersistent()) {
            return Arrays.stream(fields).filter(field -> {
                Column column = field.getAnnotation(Column.class);
                Transient transientAnnotation = field.getAnnotation(Transient.class);
                if (!Modifier.isStatic(field.getModifiers()) && transientAnnotation == null) {
                    if (column == null) {
                        return isColumnizable(field);
                    } else {
                        return ensureColumnizable(column, field, primaryField, insertable, updatable);
                    }
                } else {
                    return false;
                }
            }).toArray(Field[]::new);
        } else {
            return Arrays.stream(fields).filter(field -> {
                Column column = field.getAnnotation(Column.class);
                Transient transientAnnotation = field.getAnnotation(Transient.class);
                if (!Modifier.isStatic(field.getModifiers()) && transientAnnotation == null) {
                    if (column == null) {
                        return false;
                    } else {
                        return ensureColumnizable(column, field, primaryField, insertable, updatable);
                    }
                } else {
                    return false;
                }
            }).toArray(Field[]::new);
        }
    }

    protected String getColumnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !StringUtil.isBlank(column.name())) {
            return column.name();
        } else {
            return Inflector.getInstance().underscore(field.getName());
        }
    }

    protected boolean isColumnizable(Field field) {
        return COLUMNIZABLE_FIELD_TYPES.contains(field.getType());
    }

    private boolean ensureColumnizable(Column column, Field field, Field primaryField,
                                       boolean insertable, boolean updatable) {
        if (insertable && updatable) {
            return true;
        } else if (insertable) {
            return column.insertable();
        } else if (updatable) {
            return (updatable && column.updatable() && !field.equals(primaryField));
        } else {
            return false;
        }
    }

    private void prepareColumnToPropertyOverrides(Class<T> rowClass) {
        Field[] fields = rowClass.getDeclaredFields();
        Arrays.stream(fields).forEach(field -> {
            PrimaryKey primaryKey = field.getAnnotation(PrimaryKey.class);
            Column column = field.getAnnotation(Column.class);

            if (primaryKey != null) {
                String columnName = StringUtil.isBlank(primaryKey.name())
                        ? Inflector.getInstance().underscore(field.getName()) : primaryKey.name();
                columnToField.put(columnName, field);
                columnToField.put(columnName.toUpperCase(), field);
            } else if (column != null) {
                String columnName = StringUtil.isBlank(column.name())
                        ? Inflector.getInstance().underscore(field.getName()) : column.name();
                columnToField.put(columnName.toUpperCase(), field);
                columnToField.put(columnName, field);
            } else {
                columnToField.put(Inflector.getInstance().underscore(field.getName()), field);
                columnToField.put(Inflector.getInstance().underscore(field.getName()).toUpperCase(), field);
            }
        });
    }

    private Map<String, ColumnTransition> instantiateColumnTransitionMap(Field[] fields) {
        Arrays.stream(fields).forEach(field -> {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.transition().equals(ColumnTransition.class)) {
                columnTransitionMap.put(field.getName(), ClassUtils.createNewInstance(column.transition()));
            }
        });

        return columnTransitionMap;
    }
}
