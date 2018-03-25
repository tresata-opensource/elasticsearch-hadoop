/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.hadoop.cascading;

import java.io.IOException;
import java.util.*;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.elasticsearch.hadoop.cfg.ConfigurationOptions;
import org.elasticsearch.hadoop.cfg.HadoopSettings;
import org.elasticsearch.hadoop.cfg.HadoopSettingsManager;
import org.elasticsearch.hadoop.cfg.InternalConfigurationOptions;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.mr.EsInputFormat;
import org.elasticsearch.hadoop.mr.EsOutputFormat;
import org.elasticsearch.hadoop.mr.HadoopCfgUtils;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.RestClient;
import org.elasticsearch.hadoop.serialization.builder.JdkValueReader;
import org.elasticsearch.hadoop.util.FieldAlias;
import org.elasticsearch.hadoop.util.SettingsUtils;
import org.elasticsearch.hadoop.util.StringUtils;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.scheme.SinkCall;
import cascading.scheme.SourceCall;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;

import static org.elasticsearch.hadoop.cascading.CascadingValueWriter.SINK_CTX_ALIASES;
import static org.elasticsearch.hadoop.cascading.CascadingValueWriter.SINK_CTX_SIZE;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Cascading Scheme handling
 */
@SuppressWarnings("rawtypes")
class EsHadoopScheme extends Scheme<JobConf, RecordReader, OutputCollector, Object[], Object[]> {

    private static final long serialVersionUID = 4304172465362298925L;

    private static final int SRC_CTX_SIZE = 4;
    private static final int SRC_CTX_KEY = 0;
    private static final int SRC_CTX_VALUE = 1;
    private static final int SRC_CTX_ALIASES = 2;
    private static final int SRC_CTX_OUTPUT_JSON = 3;
    
    private static final Map<String, Class> typesMap;
    static {
        Map<String, Class> m = new java.util.HashMap<String, Class>();
        m.put("string", String.class);
        m.put("integer", int.class);
        m.put("long", long.class);
        m.put("float", float.class);
        m.put("double", double.class);
        m.put("boolean", boolean.class);
        typesMap = Collections.unmodifiableMap(m);
    }

    private final String index;
    private final String query;
    private final String nodes;
    private final int port;
    private final Properties props;
    private Class[] types = new Class[0];

    private static Log log = LogFactory.getLog(EsHadoopScheme.class);

    EsHadoopScheme(String nodes, int port, String index, String query, Fields fields, Properties props) {
        this.index = index;
        this.query = query;
        this.nodes = nodes;
        this.port = port;
        if (fields != null) {
            setSinkFields(fields);
            setSourceFields(fields);
        }
        this.props = props;
    }

    @Override
    public void sourcePrepare(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        super.sourcePrepare(flowProcess, sourceCall);

        Object[] context = new Object[SRC_CTX_SIZE];
        context[SRC_CTX_KEY] = sourceCall.getInput().createKey();
        context[SRC_CTX_VALUE] = sourceCall.getInput().createValue();
        // as the tuple _might_ vary (some objects might be missing), we use a map rather then a collection
        Settings settings = loadSettings(flowProcess.getConfigCopy(), true);
        context[SRC_CTX_ALIASES] = CascadingUtils.alias(settings);
        context[SRC_CTX_OUTPUT_JSON] = settings.getOutputAsJson();
        sourceCall.setContext(context);
    }

    @Override
    public void sourceCleanup(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        super.sourceCleanup(flowProcess, sourceCall);

        sourceCall.setContext(null);
    }

    @Override
    public void sinkPrepare(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        super.sinkPrepare(flowProcess, sinkCall);

        Object[] context = new Object[SINK_CTX_SIZE];
        // the tuple is fixed, so we can just use a collection/index
        Settings settings = loadSettings(flowProcess.getConfigCopy(), false);
        context[SINK_CTX_ALIASES] = CascadingUtils.fieldToAlias(settings, getSinkFields());
        context[1] = types;
        sinkCall.setContext(context);
    }

    public void sinkCleanup(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        super.sinkCleanup(flowProcess, sinkCall);

        sinkCall.setContext(null);
    }
    
    @Override
    public Fields retrieveSourceFields(final FlowProcess<JobConf> flowProcess, final Tap tap) {
        Settings settings = loadSettings(flowProcess.getConfigCopy(), false);
        if (getSourceFields() == Fields.UNKNOWN && settings.getFieldDetection()) {
            log.info("resource: " + index);
            String[] parts = index.split("/");
            if (parts.length == 2) {
                String myIndex = parts[0];
                String docType = parts[1];
                log.info("index: " + myIndex + ", type: " + docType);

                String mappingsUrl = "/" + myIndex + "/_mapping/" + docType;
                log.info("mapping URL: " + mappingsUrl);
                RestClient client = new RestClient(settings);
                try {
                    String responseBody = IOUtils.toString(client.getRaw(mappingsUrl));

                    // extract fields from the response body
                    JsonNode mappingsObj = new ObjectMapper().readTree(responseBody)
                        .path(myIndex).path("mappings")
                        .path(docType).path("properties");
                    Iterator<String> fieldsIterator = mappingsObj.getFieldNames();
                    List<String> fieldList = new ArrayList<String>();
                    while (fieldsIterator.hasNext())
                        fieldList.add(fieldsIterator.next());
                    String[] fieldNames = new String[fieldList.size()];
                    fieldList.toArray(fieldNames);
                    Fields fields = new Fields(fieldNames);
                    log.info("fields: " + fieldNames);
                    setSourceFields(fields);
                } catch (IOException e) {
                    log.info("no fields found in the mapping");
                } finally {
                    client.close();
                }
            }
        }
        return getSourceFields();
    }
    
    @Override
    public void sourceConfInit(FlowProcess<JobConf> flowProcess, Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {
        conf.setInputFormat(EsInputFormat.class);
        Settings set = loadSettings(conf, true);

        Collection<String> fields = CascadingUtils.fieldToAlias(set, getSourceFields());
        // load only the necessary fields
        conf.set(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS, StringUtils.concatenate(fields));

        if (log.isTraceEnabled()) {
            log.trace("Initialized (source) configuration " + HadoopCfgUtils.asProperties(conf));
        }
    }

    @Override
    public void sinkConfInit(FlowProcess<JobConf> flowProcess, Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {

        conf.setOutputFormat(EsOutputFormat.class);
        // define an output dir to prevent Cascading from setting up a TempHfs and overriding the OutputFormat
        Settings set = loadSettings(conf, false);

        Log log = LogFactory.getLog(EsTap.class);
        InitializationUtils.setValueWriterIfNotSet(set, CascadingValueWriter.class, log);
        InitializationUtils.setValueReaderIfNotSet(set, JdkValueReader.class, log);
        InitializationUtils.setBytesConverterIfNeeded(set, CascadingLocalBytesConverter.class, log);
        InitializationUtils.setFieldExtractorIfNotSet(set, CascadingFieldExtractor.class, log);

        // NB: we need to set this property even though it is not being used - and since and URI causes problem, use only the resource/file
        //conf.set("mapred.output.dir", set.getTargetUri() + "/" + set.getTargetResource());
        HadoopCfgUtils.setFileOutputFormatDir(conf, set.getResourceWrite());
        HadoopCfgUtils.setOutputCommitterClass(conf, EsOutputFormat.EsOldAPIOutputCommitter.class.getName());

        if (log.isTraceEnabled()) {
            log.trace("Initialized (sink) configuration " + HadoopCfgUtils.asProperties(conf));
        }
        
        String[] parts = index.split("/");
        
        // set the es_mapping_id if the _id : path value is set for the index/type
        if (parts.length == 2) {
            String currentId = conf.get(ConfigurationOptions.ES_MAPPING_ID);
            if (currentId == null) {
                String myIndex = parts[0];
                String docType = parts[1];
                String indexUrl = "/" + myIndex;
                log.info("index URL: " + indexUrl);
                Settings settings = new HadoopSettings(conf);
                RestClient client = new RestClient(settings);
                try {
                    java.io.InputStream response = client.getRaw(indexUrl);
                    String responseBody = IOUtils.toString(response);

                    // extract _id path
                    JsonNode mappingsObj = new ObjectMapper().readTree(responseBody)
                        .path(myIndex).path("mappings")
                        .path(docType).path("_id").path("path");
                    String idField = mappingsObj.getTextValue();
                    conf.set(ConfigurationOptions.ES_MAPPING_ID, idField);
                } catch (Exception e) {
                    // If there is no stored _id, just continue without setting it
                    log.info("No es.mapping.id specified");
                } finally {
                    client.close();
                }
            }
        }

        // grab any types stored in the index/type properties, in order to apply casts on the tuples
        if (parts.length == 2 && set.getTypeDetection()) {
            String myIndex = parts[0];
            String docType = parts[1];
            String mappingsUrl = "/" + myIndex + "/_mappings";
            log.info("mappings URL: " + mappingsUrl);
            Settings settings = new HadoopSettings(conf);
            RestClient client = new RestClient(settings);
            try {
                java.io.InputStream response = client.getRaw(mappingsUrl);
                String responseBody = IOUtils.toString(response);

                // extract map of fields to classes
                JsonNode mappingsObj = new ObjectMapper().readTree(responseBody)
                    .path(myIndex).path("mappings")
                    .path(docType).path("properties");
                Iterator<java.util.Map.Entry<String, JsonNode>> nodeIterator = mappingsObj.getFields();
                Map<String, Class> classMap = new java.util.HashMap<String, Class>();
                while (nodeIterator.hasNext()) {
                    java.util.Map.Entry<String, JsonNode> entry = nodeIterator.next();
                    classMap.put(entry.getKey(), typesMap.get(entry.getValue().findValue("type").getTextValue()));
                }

                // create array of types corresponding to the sink fields
                Fields fields = getSinkFields();
                types = new Class[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    types[i] = classMap.get(fields.get(i));
                }

            } catch (Exception e) {
                // if there are no mappings stored, continue as usual
                log.info("No field types found");
            } finally {
                client.close();
            }
        }
    }

    private Settings loadSettings(Object source, boolean read) {
        return CascadingUtils.init(HadoopSettingsManager.loadFrom(source).merge(props), nodes, port, index, query, read);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean source(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        Object[] context = sourceCall.getContext();

        if (!sourceCall.getInput().next(context[SRC_CTX_KEY], context[1])) {
            return false;
        }

        boolean isJSON = (Boolean) context[SRC_CTX_OUTPUT_JSON];

        TupleEntry entry = sourceCall.getIncomingEntry();

        Map data;
        if (isJSON) {
            data = new HashMap(1);
            data.put(new Text("data"), context[SRC_CTX_VALUE]);
        } else {
            data = (Map) context[SRC_CTX_VALUE];
        }

        FieldAlias alias = (FieldAlias) context[SRC_CTX_ALIASES];

        if (entry.getFields().isDefined()) {
            // lookup using writables
            Text lookupKey = new Text();
            for (Comparable<?> field : entry.getFields()) {
                // check for multi-level alias (since ES 1.0)
                Object result = data;
                for (String level : StringUtils.tokenize(alias.toES(field.toString()), ".")) {
                    lookupKey.set(level);
                    result = ((Map) result).get(lookupKey);
                    if (result == null) {
                        break;
                    }
                }
                CascadingUtils.setObject(entry, field, result);
            }
        }
        else {
            // no definition means no coercion
            List<Object> elements = Tuple.elements(entry.getTuple());
            elements.clear();
            elements.addAll(data.values());
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void sink(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        sinkCall.getOutput().collect(null, sinkCall);
    }
}
