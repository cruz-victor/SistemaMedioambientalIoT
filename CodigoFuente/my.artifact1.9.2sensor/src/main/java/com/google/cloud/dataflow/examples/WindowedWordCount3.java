/*
ESTE EJEMPLO FUNCIONA. AUN NO ADICIONA DATOS EN EL CLOUD DATOS EN EL BIGQUERY, 
SOLO MUESTRA EN EL LOG LA TRANSFORMACION DEL OJBETO JSON A OBJETO SENSOR

 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.examples;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.JsonParser;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.bigquery.model.JsonObject;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.dataflow.examples.WindowedWordCount2.Data;
import com.google.cloud.dataflow.examples.WindowedWordCount2.Sensor;
import com.google.cloud.dataflow.examples.WordCount.ExtractWordsFn;
import com.google.cloud.dataflow.examples.common.DataflowExampleOptions;
import com.google.cloud.dataflow.examples.common.DataflowExampleUtils;
import com.google.cloud.dataflow.examples.common.ExampleBigQueryTableOptions;
import com.google.cloud.dataflow.examples.common.ExamplePubsubTopicOptions;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.PipelineResult;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.DoFn.ProcessContext;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;

import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * An example that counts words in text, and can run over either unbounded or bounded input
 * collections.
 *
 * <p>This class, {@link WindowedWordCount3}, is the last in a series of four successively more
 * detailed 'word count' examples. First take a look at {@link MinimalWordCount},
 * {@link WordCount}, and {@link DebuggingWordCount}.
 *
 * <p>Basic concepts, also in the MinimalWordCount, WordCount, and DebuggingWordCount examples:
 * Reading text files; counting a PCollection; writing to GCS; executing a Pipeline both locally
 * and using the Dataflow service; defining DoFns; creating a custom aggregator;
 * user-defined PTransforms; defining PipelineOptions.
 *
 * <p>New Concepts:
 * <pre>
 *   1. Unbounded and bounded pipeline input modes
 *   2. Adding timestamps to data
 *   3. PubSub topics as sources
 *   4. Windowing
 *   5. Re-using PTransforms over windowed PCollections
 *   6. Writing to BigQuery
 * </pre>
 *
 * <p>To execute this pipeline locally, specify general pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 * }
 * </pre>
 *
 * <p>To execute this pipeline using the Dataflow service, specify pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --stagingLocation=gs://YOUR_STAGING_DIRECTORY
 *   --runner=BlockingDataflowPipelineRunner
 * }
 * </pre>
 *
 * <p>Optionally specify the input file path via:
 * {@code --inputFile=gs://INPUT_PATH},
 * which defaults to {@code gs://dataflow-samples/shakespeare/kinglear.txt}.
 *
 * <p>Specify an output BigQuery dataset and optionally, a table for the output. If you don't
 * specify the table, one will be created for you using the job name. If you don't specify the
 * dataset, a dataset called {@code dataflow-examples} must already exist in your project.
 * {@code --bigQueryDataset=YOUR-DATASET --bigQueryTable=YOUR-NEW-TABLE-NAME}.
 *
 * <p>Decide whether you want your pipeline to run with 'bounded' (such as files in GCS) or
 * 'unbounded' input (such as a PubSub topic). To run with unbounded input, set
 * {@code --unbounded=true}. Then, optionally specify the Google Cloud PubSub topic to read from
 * via {@code --pubsubTopic=projects/PROJECT_ID/topics/YOUR_TOPIC_NAME}. If the topic does not
 * exist, the pipeline will create one for you. It will delete this topic when it terminates.
 * The pipeline will automatically launch an auxiliary batch pipeline to populate the given PubSub
 * topic with the contents of the {@code --inputFile}, in order to make the example easy to run.
 * If you want to use an independently-populated PubSub topic, indicate this by setting
 * {@code --inputFile=""}. In that case, the auxiliary pipeline will not be started.
 *
 * <p>By default, the pipeline will do fixed windowing, on 1-minute windows.  You can
 * change this interval by setting the {@code --windowSize} parameter, e.g. {@code --windowSize=10}
 * for 10-minute windows.
 */
public class WindowedWordCount3 {
    private static final Logger LOG = LoggerFactory.getLogger(WindowedWordCount3.class);
    static final int WINDOW_SIZE = 0;  // Default window duration in minutes

  /**
   * Concept #2: A DoFn that sets the data element timestamp. This is a silly method, just for
   * this example, for the bounded data case.
   *
   * <p>Imagine that many ghosts of Shakespeare are all typing madly at the same time to recreate
   * his masterworks. Each line of the corpus will get a random associated timestamp somewhere in a
   * 2-hour period.
   */
  static class AddTimestampFn extends DoFn<String, String> {
	  
    private static final Duration RAND_RANGE = Duration.standardHours(2);
    private final Instant minTimestamp;

    AddTimestampFn() {
      this.minTimestamp = new Instant(System.currentTimeMillis());
    }

    @Override
    public void processElement(ProcessContext c) {
      // Generate a timestamp that falls somewhere in the past two hours.
      long randMillis = (long) (Math.random() * RAND_RANGE.getMillis());
      Instant randomTimestamp = minTimestamp.plus(randMillis);
      /**
       * Concept #2: Set the data element with that timestamp.
       */
      c.outputWithTimestamp(c.element(), new Instant(randomTimestamp));
    }
  }

  /** A DoFn that converts a Word and Count into a BigQuery table row. */
  static class FormatAsTableRowFn extends DoFn<KV<String, Long>, TableRow> {
    @Override
    public void processElement(ProcessContext c) {
      TableRow row = new TableRow()
    		  .set("word", c.element().getKey())
    		  .set("count", c.element().getValue())
    		  // include a field for the window timestamp
    		  .set("window_timestamp", c.timestamp().toString());
      c.output(row);
    }
  }

  /**
   * Helper method that defines the BigQuery schema used for the output.
   */
  /*private static TableSchema getSchema() {
    List<TableFieldSchema> fields = new ArrayList<>();
    fields.add(new TableFieldSchema().setName("word").setType("STRING"));
    fields.add(new TableFieldSchema().setName("count").setType("INTEGER"));
    fields.add(new TableFieldSchema().setName("window_timestamp").setType("TIMESTAMP"));
    TableSchema schema = new TableSchema().setFields(fields);
    return schema;
  }*/
  
  private static TableSchema getSchema() {
	    List<TableFieldSchema> fields = new ArrayList<>();
	    fields.add(new TableFieldSchema().setName("deviceid").setType("STRING"));
	    fields.add(new TableFieldSchema().setName("channel").setType("STRING"));
	    fields.add(new TableFieldSchema().setName("timestamp").setType("STRING"));	    
	    fields.add(new TableFieldSchema().setName("temperature").setType("STRING"));
	    fields.add(new TableFieldSchema().setName("pressure").setType("STRING"));	    
	    fields.add(new TableFieldSchema().setName("datetime").setType("DATETIME"));
	    TableSchema schema = new TableSchema().setFields(fields);
	    return schema;
	  }
  

  /**
   * Concept #6: We'll stream the results to a BigQuery table. The BigQuery output source is one
   * that supports both bounded and unbounded data. This is a helper method that creates a
   * TableReference from input options, to tell the pipeline where to write its BigQuery results.
   */
  private static TableReference getTableReference(Options options) {
    TableReference tableRef = new TableReference();
    tableRef.setProjectId(options.getProject());
    tableRef.setDatasetId(options.getBigQueryDataset());
    tableRef.setTableId(options.getBigQueryTable());
    return tableRef;
  }
  
/*
   * Options supported by {@link WindowedWordCount}.
   *
   * <p>Inherits standard example configuration options, which allow specification of the BigQuery
   * table and the PubSub topic, as well as the {@link WordCount.WordCountOptions} support for
   * specification of the input file.
*/
  public interface Options extends WordCount.WordCountOptions,
      DataflowExampleOptions, ExamplePubsubTopicOptions, ExampleBigQueryTableOptions {
    @Description("Fixed window duration, in minutes")
    @Default.Integer(WINDOW_SIZE)
    Integer getWindowSize();
    void setWindowSize(Integer value);

    @Description("Whether to run the pipeline with unbounded input")
    boolean isUnbounded();
    void setUnbounded(boolean value);
  }
  
  
//  public static class TransformarDatosSensor extends PTransform<PCollection<String>, PCollection<KV<String, Long>>>
//  {
//	@Override
//	public PCollection<KV<String, Long>> apply(PCollection<String> lineas)
//	{
//		
//	}
//	
//	
//  }
//  
    
  static class ExtractWordsFn extends DoFn<String, String> {	    
	    @Override
	    public void processElement(ProcessContext c) {
	    	LOG.info("---->Dentro de la funcion ExtractWordsFn");
	    	LOG.info("---->element:"+c.element());
	    	
	    	Sensor sensor=parseJsonToObject2(c.element());
//	    	
	    	LOG.info("---->Sensor1:::"+ sensor.getChannel());
	    	LOG.info("---->Sensor2:::"+ sensor.getDeviceId());
	    	
	        c.output(c.element());	          
	    }  
  }
  
  static Sensor parseJsonToObject(String entry)
  { 
	  //Sensor sensor=new Sensor();
	  Sensor sensor=null;
	  try
	  {
		  JsonParser parser=new JacksonFactory().createJsonParser(entry);
		  sensor=parser.parse(Sensor.class);
		  		  
		  return sensor;
	  }
	  catch(IOException e)
	  {
		  LOG.info("------>Ocurrio un error al intentar parsear el objeto json al tipo objeto sensor");
	  }
	  return sensor;	  
  }
  
  
  static Sensor parseJsonToObject2(String entry)
  { 
	  Sensor sensor=null;
	  
	  try
	  {			  
		  ObjectMapper mapper=new ObjectMapper();		
		  sensor=mapper.readValue(entry, Sensor.class);
		  
		  return sensor;
	  }
	  catch(IOException e)
	  {
		  LOG.info("------>Ocurrio un error al intentar parsear el objeto json al tipo objeto sensor");		  
	  }
	  return sensor;	  
  }


  public static class Data {

  private String temperature;
  private String pressure;

  public String getTemperature() {
  return temperature;
  }

  public void setTemperature(String temperature) {
  this.temperature = temperature;
  }

  public String getPressure() {
  return pressure;
  }

  public void setPressure(String pressure) {
  this.pressure = pressure;
  }

  }
  

  public static class Sensor {

  private String deviceId;
  private String channel;
  private Long timestamp;
  private Data data;
  
  public String getDeviceId() {
  return deviceId;
  }

  public void setDeviceId(String deviceId) {
  this.deviceId = deviceId;
  }

  public String getChannel() {
  return channel;
  }

  public void setChannel(String channel) {
  this.channel = channel;
  }

  public Long getTimestamp() {
  return timestamp;
  }

  public void setTimestamp(Long timestamp) {
  this.timestamp = timestamp;
  }

  public Data getData() {
  return data;
  }

  public void setData(Data data) {
  this.data = data;
  }

  }  
    

  public static void main(String[] args) throws IOException {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    options.setBigQuerySchema(getSchema());
    
//    DataflowExampleUtils creates the necessary input sources to simplify execution of this
//    Pipeline.
    
    DataflowExampleUtils exampleDataflowUtils = new DataflowExampleUtils(options, options.isUnbounded());

    Pipeline pipeline = Pipeline.create(options);

    /**
     * Concept #1: the Dataflow SDK lets us run the same pipeline with either a bounded or
     * unbounded input source.
     */
    
    PCollection<String> input;
    if (options.isUnbounded()) {
      LOG.info("------>leendo desde PubSub.");
      /**
       * Concept #3: Read from the PubSub topic. A topic will be created if it wasn't
       * specified as an argument. The data elements' timestamps will come from the pubsub
       * injection.
       */
      input = pipeline
          .apply(PubsubIO.Read.topic(options.getPubsubTopic()));
    } else {
      /** Else, this is a bounded pipeline. Read from the GCS file. */
      input = pipeline
          .apply(TextIO.Read.from(options.getInputFile()))
          // Concept #2: Add an element timestamp, using an artificial time just to show windowing.
          // See AddTimestampFn for more detail on this.
          .apply(ParDo.of(new AddTimestampFn()));
    }

    /**
     * Concept #4: Window into fixed windows. The fixed window size for this example defaults to 1
     * minute (you can change this with a command-line option). See the documentation for more
     * information on how fixed windows work, and for information on the other types of windowing
     * available (e.g., sliding windows).
     */
    
    LOG.info("------>Ventana fija.");
    //PCollection<String> windowedWords = input.apply(Window.<String>into(FixedWindows.of(Duration.standardMinutes(options.getWindowSize()))));

    
    //PCollection<String> words = windowedWords.apply(ParDo.of(new ExtractWordsFn()));
    PCollection<String> words = input.apply(ParDo.of(new ExtractWordsFn()));
    
    
    /**
     * Concept #5: Re-use our existing CountWords transform that does not have knowledge of
     * windows over a PCollection containing windowed values.
     */
    
    ////////////PCollection<KV<String, Long>> wordCounts = windowedWords.apply(new WordCount.CountWords());

    /**
     * Concept #6: Format the results for a BigQuery table, then write to BigQuery.
     * The BigQuery output source supports both bounded and unbounded data.
     */
    
//    wordCounts.apply(ParDo.of(new FormatAsTableRowFn()))
//        .apply(BigQueryIO.Write
//          .to(getTableReference(options))
//          .withSchema(getSchema())
//          .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
//          .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND));

    
    
    PipelineResult result = pipeline.run();

    /**
     * To mock unbounded input from PubSub, we'll now start an auxiliary 'injector' pipeline that
     * runs for a limited time, and publishes to the input PubSub topic.
     *
     * With an unbounded input source, you will need to explicitly shut down this pipeline when you
     * are done with it, so that you do not continue to be charged for the instances. You can do
     * this via a ctrl-C from the command line, or from the developer's console UI for Dataflow
     * pipelines. The PubSub topic will also be deleted at this time.
     */
    
    ////////////exampleDataflowUtils.mockUnboundedSource(options.getInputFile(), result);
  }
}
