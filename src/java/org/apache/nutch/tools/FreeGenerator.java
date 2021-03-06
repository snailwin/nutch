/**
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

package org.apache.nutch.tools;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.Generator;
import org.apache.nutch.crawl.URLPartitioner;
import org.apache.nutch.net.URLFilters;
import org.apache.nutch.net.URLNormalizers;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TimingUtil;

/**
 * This tool generates fetchlists (segments to be fetched) from plain text files
 * containing one URL per line. It's useful when arbitrary URL-s need to be
 * fetched without adding them first to the CrawlDb, or during testing.
 */
public class FreeGenerator extends Configured implements Tool {
  private static final Logger LOG = LoggerFactory
      .getLogger(FreeGenerator.class);

  private static final String FILTER_KEY = "free.generator.filter";
  private static final String NORMALIZE_KEY = "free.generator.normalize";

  public static class FG extends MapReduceBase implements
      Mapper<WritableComparable<?>, Text, Text, Generator.SelectorEntry>,
      Reducer<Text, Generator.SelectorEntry, Text, CrawlDatum> {
    private URLNormalizers normalizers = null;
    private URLFilters filters = null;
    private ScoringFilters scfilters;
    private CrawlDatum datum = new CrawlDatum();
    private Text url = new Text();
    private int defaultInterval = 0;

    @Override
    public void configure(JobConf job) {
      super.configure(job);
      defaultInterval = job.getInt("db.fetch.interval.default", 0);
      scfilters = new ScoringFilters(job);
      if (job.getBoolean(FILTER_KEY, false)) {
        filters = new URLFilters(job);
      }
      if (job.getBoolean(NORMALIZE_KEY, false)) {
        normalizers = new URLNormalizers(job, URLNormalizers.SCOPE_INJECT);
      }
    }

    Generator.SelectorEntry entry = new Generator.SelectorEntry();

    public void map(WritableComparable<?> key, Text value,
        OutputCollector<Text, Generator.SelectorEntry> output, Reporter reporter)
        throws IOException {
      // value is a line of text
      String urlString = value.toString();
      try {
        if (normalizers != null) {
          urlString = normalizers.normalize(urlString,
              URLNormalizers.SCOPE_INJECT);
        }
        if (urlString != null && filters != null) {
          urlString = filters.filter(urlString);
        }
        if (urlString != null) {
          url.set(urlString);
          scfilters.injectedScore(url, datum);
        }
      } catch (Exception e) {
        LOG.warn("Error adding url '" + value.toString() + "', skipping: "
            + StringUtils.stringifyException(e));
        return;
      }
      if (urlString == null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("- skipping " + value.toString());
        }
        return;
      }
      entry.datum = datum;
      entry.url = url;
      // https://issues.apache.org/jira/browse/NUTCH-1430
      entry.datum.setFetchInterval(defaultInterval);
      output.collect(url, entry);
    }

    public void reduce(Text key, Iterator<Generator.SelectorEntry> values,
        OutputCollector<Text, CrawlDatum> output, Reporter reporter)
        throws IOException {
      // pick unique urls from values - discard the reduce key due to hash
      // collisions
      HashMap<Text, CrawlDatum> unique = new HashMap<Text, CrawlDatum>();
      while (values.hasNext()) {
        Generator.SelectorEntry entry = values.next();
        unique.put(entry.url, entry.datum);
      }
      // output unique urls
      for (Entry<Text, CrawlDatum> e : unique.entrySet()) {
        output.collect(e.getKey(), e.getValue());
      }
    }
  }

  public int run(String[] args) throws Exception {
    if (args.length < 2) {
      System.err
          .println("Usage: FreeGenerator <inputDir> <segmentsDir> [-filter] [-normalize]");
      System.err
          .println("\tinputDir\tinput directory containing one or more input files.");
      System.err
          .println("\t\tEach text file contains a list of URLs, one URL per line");
      System.err
          .println("\tsegmentsDir\toutput directory, where new segment will be created");
      System.err.println("\t-filter\trun current URLFilters on input URLs");
      System.err
          .println("\t-normalize\trun current URLNormalizers on input URLs");
      return -1;
    }
    boolean filter = false;
    boolean normalize = false;
    if (args.length > 2) {
      for (int i = 2; i < args.length; i++) {
        if (args[i].equals("-filter")) {
          filter = true;
        } else if (args[i].equals("-normalize")) {
          normalize = true;
        } else {
          LOG.error("Unknown argument: " + args[i] + ", exiting ...");
          return -1;
        }
      }
    }

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    LOG.info("FreeGenerator: starting at " + sdf.format(start));

    JobConf job = new NutchJob(getConf());
    job.setBoolean(FILTER_KEY, filter);
    job.setBoolean(NORMALIZE_KEY, normalize);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    job.setInputFormat(TextInputFormat.class);
    job.setMapperClass(FG.class);
    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(Generator.SelectorEntry.class);
    job.setPartitionerClass(URLPartitioner.class);
    job.setReducerClass(FG.class);
    String segName = Generator.generateSegmentName();
    job.setNumReduceTasks(job.getNumMapTasks());
    job.setOutputFormat(SequenceFileOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(CrawlDatum.class);
    job.setOutputKeyComparatorClass(Generator.HashComparator.class);
    FileOutputFormat.setOutputPath(job, new Path(args[1], new Path(segName,
        CrawlDatum.GENERATE_DIR_NAME)));
    try {
      JobClient.runJob(job);
    } catch (Exception e) {
      LOG.error("FAILED: " + StringUtils.stringifyException(e));
      return -1;
    }
    long end = System.currentTimeMillis();
    LOG.info("FreeGenerator: finished at " + sdf.format(end) + ", elapsed: "
        + TimingUtil.elapsedTime(start, end));
    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(NutchConfiguration.create(), new FreeGenerator(),
        args);
    System.exit(res);
  }
}
