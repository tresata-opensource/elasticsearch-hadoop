package org.elasticsearch.spark.sql;

import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.serialization.FieldType;
import org.elasticsearch.hadoop.serialization.dto.mapping.Field;
import org.elasticsearch.hadoop.util.unit.Booleans;

abstract class Utils {

    // required since type has a special meaning in Scala
    // and thus the method cannot be called
    static FieldType extractType(Field field) {
        return field.type();
    }

    static final String ROW_INFO_ORDER_PROPERTY = "es.internal.spark.sql.row.order";
    static final String ROW_INFO_ARRAY_PROPERTY = "es.internal.spark.sql.row.arrays";
    static final String ROOT_LEVEL_NAME = "_";

    static final String DATA_SOURCE_PUSH_DOWN = "es.internal.spark.sql.pushdown";
    static final String DATA_SOURCE_PUSH_DOWN_STRICT = "es.internal.spark.sql.pushdown.strict";

    static boolean isPushDown(Settings cfg) {
        return Booleans.parseBoolean(cfg.getProperty(DATA_SOURCE_PUSH_DOWN), true);
    }

    static boolean isPushDownStrict(Settings cfg) {
        return Booleans.parseBoolean(cfg.getProperty(DATA_SOURCE_PUSH_DOWN_STRICT), false);
    }

    static String camelCaseToDotNotation(String string) {
        StringBuilder sb = new StringBuilder();

        char last = 0;
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (Character.isUpperCase(c) && Character.isLowerCase(last)) {
                sb.append(".");
            }
            last = c;
            sb.append(Character.toLowerCase(c));
        }

        return sb.toString();
    }
}

