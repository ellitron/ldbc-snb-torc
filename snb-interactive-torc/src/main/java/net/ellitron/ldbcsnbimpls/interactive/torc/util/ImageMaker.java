/* 
 * Copyright (C) 2016 Stanford University
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.ellitron.ldbcsnbimpls.interactive.torc.util;

import net.ellitron.ldbcsnbimpls.interactive.core.SnbEntity;
import net.ellitron.ldbcsnbimpls.interactive.core.SnbRelation;
import net.ellitron.ldbcsnbimpls.interactive.torc.TorcEntity;
import net.ellitron.torc.TorcEdgeDirection;
import net.ellitron.torc.TorcGraph;
import net.ellitron.torc.TorcVertex;
import net.ellitron.torc.util.UInt128;

import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Graph;

import org.apache.log4j.Logger;

import org.docopt.Docopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

/**
 * A utility for converting dataset files generated by the LDBC SNB Data
 * Generator[1] into TorcDB[2] image files.
 *
 * [1]: git@github.com:ldbc/ldbc_snb_datagen.git<br>
 * [2]: git@github.com:ellitron/torc.git<br>
 *
 * @author Jonathan Ellithorpe (jde@cs.stanford.edu)
 */
public class ImageMaker {

  private static final Logger logger = Logger.getLogger(ImageMaker.class);

  private static final String doc =
      "ImageMaker: A utility for converting dataset files generated by the\n"
      + "LDBC SNB Data Generator into TorcDB image files. Nodes and edges can\n"
      + "be loaded separately using the \"nodes\" or \"edges\" mode option.\n"
      + "\n"
      + "Usage:\n"
      + "  ImageMaker [options] SOURCE\n"
      + "  ImageMaker (-h | --help)\n"
      + "  ImageMaker --version\n"
      + "\n"
      + "Arguments:\n"
      + "  SOURCE  Directory containing SNB dataset files.\n"
      + "\n"
      + "Options:\n"
      + "  --mode=<mode>     Can be either \"nodes\" or \"edges\" to create\n"
      + "                    images files for nodes or edges only.\n"
      + "                    [default: all].\n"
      + "  --outputDir=<d>   Directory to write image files [default: ./].\n"
      + "  --graphName=<g>   The name to give the graph in RAMCloud\n"
      + "                    [default: graph].\n"
      + "  --numLoaders=<n>  The total number of loader instances loading\n"
      + "                    the graph in parallel [default: 1].\n"
      + "  --loaderIdx=<n>   Among numLoaders instance, which loader this\n"
      + "                    instance represents. Defines the partition of\n"
      + "                    the dataset this loader instance is responsible\n"
      + "                    for loading. Indexes start from 0.\n"
      + "                    [default: 0].\n"
      + "  --numThreads=<n>  The number of threads to use in this loader\n"
      + "                    instance.\n This loader's dataset partition is\n"
      + "                    divided up among this number of threads."
      + "                    [default: 1].\n"
      + "  --reportInt=<i>   Number of seconds between reporting status to\n"
      + "                    the screen. [default: 10].\n"
      + "  --reportFmt=<s>   Format options for status report output.\n"
      + "                      L - Total lines processed per second.\n"
      + "                      l - Per thread lines processed per second.\n"
      + "                      F - Total files processed.\n"
      + "                      f - Per thread files processed.\n"
      + "                      D - Total disk read bandwidth in MB/s.\n"
      + "                      d - Per thread disk read bandwidth in KB/s.\n"
      + "                      T - Total time elapsed.\n"
      + "                    [default: LFDT].\n"
      + "  -h --help         Show this screen.\n"
      + "  --version         Show version.\n"
      + "\n";

  /**
   * Packs a unit of loading work for a loader thread to do. Includes a path to
   * the file to load, and what that file represents (either an SNB entity or
   * an SNB relation).
   */
  private static class LoadUnit {

    private SnbEntity entity;
    private SnbRelation relation;
    private Path filePath;
    private boolean isProperties;
    private boolean isReverseIndex;

    /**
     * Constructor for LoadUnit.
     *
     * @param entity The entity this file pertains to.
     * @param filePath The path to the file.
     * @param isProperties Whether or not this is a file containing properties
     * for the entity.
     */
    public LoadUnit(SnbEntity entity, Path filePath, boolean isProperties) {
      this.entity = entity;
      this.relation = null;
      this.filePath = filePath;
      this.isProperties = isProperties;
      this.isReverseIndex = false;
    }

    /**
     * Constructor for LoadUnit.
     *
     * @param relation The relations this file pertains to.
     * @param filePath The path to the file.
     * @param isReverseIndex Whether or not this is a file containing the
     * reverse index of the relation (contains "InVertex|OutVertex" instead of
     * "OutVertex|InVertex").
     */
    public LoadUnit(SnbRelation relation, Path filePath, 
        boolean isReverseIndex) {
      this.entity = null;
      this.relation = relation;
      this.filePath = filePath;
      this.isProperties = false;
      this.isReverseIndex = isReverseIndex;
    }

    public boolean isEntity() {
      return entity != null;
    }

    public boolean isProperties() {
      return isProperties;
    }

    public boolean isRelation() {
      return relation != null;
    }

    public boolean isReverseIndex() {
      return isReverseIndex;
    }

    public SnbEntity getSnbEntity() {
      return entity;
    }

    public SnbRelation getSnbRelation() {
      return relation;
    }

    public Path getFilePath() {
      return filePath;
    }
  }

  /**
   * A set of per-thread loading statistics. Each loader thread continually
   * updates these statistics, while a statistics reporting thread with a
   * reference to each ThreadStats instance in the system regularly prints
   * statistic summaries to the screen.
   */
  private static class ThreadStats {

    /*
     * The total number of lines this thread has successfully processed and
     * loaded into the database.
     */
    public long linesProcessed;

    /*
     * The total number of bytes this thread has read from disk.
     */
    public long bytesReadFromDisk;

    /*
     * The total number of files this thread has successfully processed and
     * loaded into the database.
     */
    public long filesProcessed;

    /*
     * The total number of files this thread has been given to process.
     */
    public long totalFilesToProcess;

    /**
     * Constructor.
     */
    public ThreadStats() {
      this.linesProcessed = 0;
      this.bytesReadFromDisk = 0;
      this.filesProcessed = 0;
      this.totalFilesToProcess = 0;
    }

    /**
     * Copy constructor.
     *
     * @param stats ThreadStats object to copy.
     */
    public ThreadStats(ThreadStats stats) {
      this.linesProcessed = stats.linesProcessed;
      this.bytesReadFromDisk = stats.bytesReadFromDisk;
      this.filesProcessed = stats.filesProcessed;
      this.totalFilesToProcess = stats.totalFilesToProcess;
    }
  }

  /**
   * A loader thread which takes a set of files to load and loads them
   * sequentially.
   */
  private static class LoaderThread implements Runnable {

    private final TorcGraph graph;
    private final List<LoadUnit> loadList;
    private final int totalThreads;
    private final int threadIdx;
    private final ThreadStats stats;

    /*
     * Used for parsing dates in the original dataset files output by the data
     * generator, and converting them to milliseconds since Jan. 1 9170. We
     * store dates in this form in TorcDB.
     */
    private final SimpleDateFormat birthdayDateFormat;
    private final SimpleDateFormat creationDateDateFormat;

    /*
     * Used for generating random backoff times in the event of repeated
     * transaction failures.
     */
    private final Random rand;

    /**
     * Constructor for LoaderThread.
     *
     * @param graph Graph into which to load the files.
     * @param loadList Master list of all files in the dataset. All loader
     * threads have a copy of this.
     * @param totalThreads Total number of loader threads in the system.
     * @param threadIdx The index of this particular loader thread.
     * @param stats ThreadStats instance to update with loading statistics
     * info.
     */
    public LoaderThread(TorcGraph graph, List<LoadUnit> loadList, 
        int totalThreads, int threadIdx, ThreadStats stats) {
      this.graph = graph;
      this.loadList = loadList;
      this.totalThreads = totalThreads;
      this.threadIdx = threadIdx;
      this.stats = stats;

      this.birthdayDateFormat =
          new SimpleDateFormat("yyyy-MM-dd");
      this.birthdayDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
      this.creationDateDateFormat =
          new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
      this.creationDateDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

      this.rand = new Random();
    }

    @Override
    public void run() {
      // Update this thread's total file load stat.
      int q = loadList.size() / totalThreads;
      int r = loadList.size() % totalThreads;
      int totalFiles;
      if ( threadIdx < r )
        totalFiles = q + 1;
      else
        totalFiles = q;

      stats.totalFilesToProcess = totalFiles;

      /* Load every (totalThreads)th file, starting with threadIdx, so:
       * loadList[threadIdx]
       * loadList[totalThreads + threadIdx]
       * loadList[2 * totalThreads + threadIdx]
       * loadList[3 * totalThreads + threadIdx]
       * ...
       * Picking files this way distributes work more evenly across the threads
       * when large files are grouped together in the list.
       */
      for (int f = 0; f < totalFiles; f++) {
        LoadUnit loadUnit = loadList.get(f * totalThreads + threadIdx);
        Path path = loadUnit.getFilePath();

        BufferedReader inFile;
        try {
          inFile = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error opening "
              + "file %s", path.getFileName()));
        }

        System.out.println(String.format("Thread %d: Loading file: %s",
            threadIdx, path.getFileName().toString()));

        // First line of the file contains the column headers.
        String[] fieldNames;
        try {
          fieldNames = inFile.readLine().split("\\|");
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error reading "
              + "header line of file %s", path.getFileName()));
        }

        // Keep track of what lines we're on in this file.
        long localLinesProcessed = 0;

        try {
          if (loadUnit.isEntity() && !loadUnit.isProperties()) {
            // Vertex file
            SnbEntity snbEntity = loadUnit.getSnbEntity();
            long idSpace = TorcEntity.valueOf(snbEntity).idSpace;
            String vertexLabel = TorcEntity.valueOf(snbEntity).label;

            String line;
            while ((line = inFile.readLine()) != null) {
              UInt128 vertexId = null;
              Map<String, List<String>> propMap = new HashMap<>();
              String[] fieldValues = line.split("\\|");
              for (int j = 0; j < fieldValues.length; j++) {
                try {
                  List<String> propValues = new ArrayList<>(8);
                  if (fieldNames[j].equals("id")) {
                    vertexId = new UInt128(idSpace, Long.decode(fieldValues[j]));
                  } else if (fieldNames[j].equals("birthday")) {
                    propValues.add(String.valueOf(
                        birthdayDateFormat.parse(fieldValues[j]).getTime()));
                  } else if (fieldNames[j].equals("creationDate") || 
                      fieldNames[j].equals("joinDate")) {
                    propValues.add(String.valueOf(
                        creationDateDateFormat.parse(fieldValues[j]).getTime()));
                  } else if (fieldNames[j].equals("email") || 
                      fieldNames[j].equals("language")) {
                    String[] elements = fieldValues[j].split(";");
                    for (String elem : elements) {
                      if (elem.length() != 0) {
                        propValues.add(elem);
                      }
                    }
                  } else {
                    propValues.add(fieldValues[j]);
                  }

                  if (propMap.containsKey(fieldNames[j])) {
                    propMap.get(fieldNames[j]).addAll(propValues);
                  } else {
                    propMap.put(fieldNames[j], propValues);
                  }
                } catch (Exception ex) {
                  throw new RuntimeException(String.format("Encountered "
                      + "error processing field %s with value %s of line %d "
                      + "in the line buffer. Line: \"%s\"", fieldNames[j],
                      fieldValues[j], localLinesProcessed + 1, line), ex);
                }
              }

              graph.loadVertex(vertexId, vertexLabel, propMap);

              localLinesProcessed++;
              stats.linesProcessed++;
            }
          } else if (loadUnit.isRelation()) {
            SnbRelation snbRelation = loadUnit.getSnbRelation();

            // Normally edges are listed started with the vertex on the tail end
            // of the edge, followed by the vertex on the head end of the edge.
            // But in reverse indexes, it is the opposite.
            TorcEntity baseEntity;
            TorcEntity neighborEntity;
            TorcEdgeDirection edgeDir;
            if (loadUnit.isReverseIndex()) {
              baseEntity = TorcEntity.valueOf(snbRelation.head);
              neighborEntity = TorcEntity.valueOf(snbRelation.tail);
              edgeDir = TorcEdgeDirection.DIRECTED_IN;
            } else {
              baseEntity = TorcEntity.valueOf(snbRelation.tail);
              neighborEntity = TorcEntity.valueOf(snbRelation.head);
              edgeDir = TorcEdgeDirection.DIRECTED_OUT;
            }

            UInt128 curBaseVertexId = null;
            List<UInt128> neighborIds = new ArrayList<>(32);
            List<Map<String, List<String>>> propMaps = new ArrayList<>();
            String line;
            while ((line = inFile.readLine()) != null) {
              String[] fieldValues = line.split("\\|");
              
              UInt128 baseVertexId = 
                new UInt128(baseEntity.idSpace, Long.decode(fieldValues[0]));

              if (curBaseVertexId == null) {
                curBaseVertexId = baseVertexId;
              } else if (!baseVertexId.equals(curBaseVertexId)) {
                // We hit a new set of edges in the file, so we should write out
                // the edge list that we currently have buffered before starting
                // on this new edge list.
                graph.loadEdges(curBaseVertexId, snbRelation.name, edgeDir, 
                    neighborEntity.label, neighborIds, propMaps);
                
                curBaseVertexId = baseVertexId;
                neighborIds.clear();
                propMaps.clear();
              }

              UInt128 neighborId = 
                new UInt128(neighborEntity.idSpace, Long.decode(fieldValues[1]));

              neighborIds.add(neighborId);
              
              // Parse properties only if these edges have properties.
              if (fieldValues.length > 2) {
                Map<String, List<String>> propMap = new HashMap<>();
                for (int j = 2; j < fieldValues.length; j++) {
                  try {
                    List<String> propValues = new ArrayList<>(8);
                    if (fieldNames[j].equals("creationDate") || 
                        fieldNames[j].equals("joinDate")) {
                      propValues.add(String.valueOf(
                          creationDateDateFormat.parse(fieldValues[j]).getTime()));
                    } else {
                      propValues.add(fieldValues[j]);
                    }

                    if (propMap.containsKey(fieldNames[j])) {
                      propMap.get(fieldNames[j]).addAll(propValues);
                    } else {
                      propMap.put(fieldNames[j], propValues);
                    }
                  } catch (Exception ex) {
                    throw new RuntimeException(String.format("Encountered "
                        + "error processing field %s with value %s of line %d "
                        + "in the line buffer. Line: \"%s\"", fieldNames[j],
                        fieldValues[j], localLinesProcessed + 1, line), ex);
                  }
                }

                propMaps.add(propMap);
              }
                
              localLinesProcessed++;
              stats.linesProcessed++;
              stats.bytesReadFromDisk += line.length();
            }

            if (curBaseVertexId != null) {
              graph.loadEdges(curBaseVertexId, snbRelation.name, edgeDir, 
                  neighborEntity.label, neighborIds, propMaps);
            }
          }
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }

        try {
          inFile.close();
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error closing "
              + "file %s", path.getFileName()));
        }
        stats.filesProcessed++;
      }
    }
  }

  /**
   * A thread which reports statistics on the loader threads in the system at a
   * set interval. This thread gets information on each thread via a shared
   * ThreadStats object with each thread.
   */
  private static class StatsReporterThread implements Runnable {

    private List<ThreadStats> threadStats;
    private long reportInterval;
    private String formatString;

    /**
     * Constructor for StatsReporterThread.
     *
     * @param threadStats List of all the shared ThreadStats objects, one per
     * thread.
     * @param reportInterval Interval, in seconds, to report statistics to the
     * screen.
     */
    public StatsReporterThread(List<ThreadStats> threadStats,
        long reportInterval, String formatString) {
      this.threadStats = threadStats;
      this.reportInterval = reportInterval;
      this.formatString = formatString;
    }

    @Override
    public void run() {
      try {
//      L - Total lines processed per second.
//      l - Per thread lines processed per second.
//      F - Total files processed per second.
//      f - Per thread files processed per second.
//      D - Total disk read bandwidth in MB/s.
//      d - Per thread disk read bandwidth in KB/s.
//      T - Total time elapsed.

        // Print the column headers.
        String colFormatStr = "%10s";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < threadStats.size(); i++) {
          if (formatString.contains("l")) {
            sb.append(String.format(colFormatStr, i + ".l"));
          }

          if (formatString.contains("f")) {
            sb.append(String.format(colFormatStr, i + ".f"));
          }

          if (formatString.contains("x")) {
            sb.append(String.format(colFormatStr, i + ".x"));
          }

          if (formatString.contains("d")) {
            sb.append(String.format(colFormatStr, i + ".d"));
          }
        }

        if (formatString.contains("L")) {
          sb.append(String.format(colFormatStr, "L"));
        }

        if (formatString.contains("F")) {
          sb.append(String.format(colFormatStr, "F"));
        }

        if (formatString.contains("X")) {
          sb.append(String.format(colFormatStr, "X"));
        }

        if (formatString.contains("D")) {
          sb.append(String.format(colFormatStr, "D"));
        }

        if (formatString.contains("T")) {
          sb.append(String.format(colFormatStr, "T"));
        }

        String headerString = sb.toString();

        System.out.println(headerString);

        // Capture the thread stats now before entering report loop. We'll use
        // this to start reporting diffs right away.
        List<ThreadStats> lastThreadStats = new ArrayList<>();
        for (int i = 0; i < threadStats.size(); i++) {
          lastThreadStats.add(new ThreadStats(threadStats.get(i)));
        }

        long startTime = System.currentTimeMillis();
        while (true) {
          Thread.sleep(reportInterval * 1000l);

          // Time elapsed since beginning loading.
          long timeElapsed = (System.currentTimeMillis() - startTime) / 1000l;

          sb = new StringBuilder();
          long totalCurrLineRate = 0;
          long totalCurrByteRate = 0;
          long totalFilesProcessed = 0;
          long totalTxFailures = 0;
          long totalFilesToProcess = 0;
          for (int i = 0; i < threadStats.size(); i++) {
            ThreadStats lastStats = lastThreadStats.get(i);
            ThreadStats currStats = threadStats.get(i);

            long linesProcessed =
                currStats.linesProcessed - lastStats.linesProcessed;
            long bytesReadFromDisk =
                currStats.bytesReadFromDisk - lastStats.bytesReadFromDisk;

            long currLineRate = linesProcessed / reportInterval;
            long currByteRate = bytesReadFromDisk / reportInterval;

            if (formatString.contains("l")) {
              sb.append(String.format(colFormatStr, currLineRate));
            }

            if (formatString.contains("f")) {
              sb.append(String.format(colFormatStr, String.format("(%d/%d)",
                  currStats.filesProcessed, currStats.totalFilesToProcess)));
            }

            if (formatString.contains("d")) {
              sb.append(String.format(colFormatStr, (currByteRate / 1000l)
                  + "KB/s"));
            }

            totalCurrLineRate += currLineRate;
            totalCurrByteRate += currByteRate;
            totalFilesProcessed += currStats.filesProcessed;
            totalFilesToProcess += currStats.totalFilesToProcess;
          }

          if (formatString.contains("L")) {
            sb.append(String.format(colFormatStr, totalCurrLineRate));
          }

          if (formatString.contains("F")) {
            sb.append(String.format(colFormatStr, String.format("(%d/%d)",
                totalFilesProcessed, totalFilesToProcess)));
          }

          if (formatString.contains("X")) {
            sb.append(String.format(colFormatStr, totalTxFailures));
          }

          if (formatString.contains("D")) {
            sb.append(String.format(colFormatStr,
                (totalCurrByteRate / 1000000l) + "MB/s"));
          }

          if (formatString.contains("T")) {
            sb.append(String.format(colFormatStr, (timeElapsed / 60l) + "m"));
          }

          System.out.println(sb.toString());

          lastThreadStats.clear();
          for (int i = 0; i < threadStats.size(); i++) {
            lastThreadStats.add(new ThreadStats(threadStats.get(i)));
          }

          // Check if we are done loading. If so, exit.
          if (totalFilesProcessed == totalFilesToProcess) {
            break;
          }
        }
      } catch (InterruptedException ex) {
        // This is fine, we're probably being terminated, in which case we 
        // should just go ahead and terminate.
      }
    }
  }

  public static void main(String[] args)
      throws FileNotFoundException, IOException, ParseException, InterruptedException {
    Map<String, Object> opts =
        new Docopt(doc).withVersion("ImageMaker 1.0").parse(args);

    System.out.println(opts.toString());

    String mode = (String) opts.get("--mode");
    String outputDir = (String) opts.get("--outputDir");
    String graphName = (String) opts.get("--graphName");
    int numLoaders = Integer.decode((String) opts.get("--numLoaders"));
    int loaderIdx = Integer.decode((String) opts.get("--loaderIdx"));
    int numThreads = Integer.decode((String) opts.get("--numThreads"));
    long reportInterval = Long.decode((String) opts.get("--reportInt"));
    String formatString = (String) opts.get("--reportFmt");
    String inputDir = (String) opts.get("SOURCE");

    System.out.println(String.format(
        "ImageMaker: {mode: %s, outputDir: %s, graphName: %s, "
        + "numLoaders: %d, loaderIdx: %d, "
        + "reportFmt: %s, inputDir: %s}",
        mode,
        outputDir,
        graphName, 
        numLoaders,
        loaderIdx,
        formatString,
        inputDir));

    // Construct a list of all the files that need to be loaded.
    List<LoadUnit> loadList = new ArrayList<>();
    File dir = new File(inputDir);
    int totalNodeFiles = 0;
    if (mode.equals("all") || mode.equals("nodes")) {
      for (SnbEntity snbEntity : SnbEntity.values()) {
        File [] fileList = dir.listFiles(new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.matches(
                    "^" + snbEntity.name + "_[0-9]+_[0-9]+\\.csv");
              }
            });

        if (fileList.length > 0) {
          for (File f : fileList) {
            loadList.add(new LoadUnit(snbEntity, f.toPath(), false));
            System.out.println(String.format("Found file for %s nodes (%s)",
                snbEntity.name, f.getName()));
            totalNodeFiles++;
          }
        } else {
          System.out.println(String.format("Missing files for %s nodes",
              snbEntity.name));
        }
      }

      System.out.println(String.format("Found %d total node files",
          totalNodeFiles));
    } 

    int totalEdgeFiles = 0;
    if (mode.equals("all") || mode.equals("edges")) {
      for (SnbRelation snbRelation : SnbRelation.values()) {

        String edgeFormatStr;
        if (snbRelation.directed) {
          edgeFormatStr = "(%s)-[%s]->(%s)";
        } else {
          edgeFormatStr = "(%s)-[%s]-(%s)";
        }

        String edgeStr = String.format(edgeFormatStr,
            snbRelation.tail.name,
            snbRelation.name,
            snbRelation.head.name);

        File [] fileList;
        fileList = dir.listFiles(new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.matches(
                    "^" + snbRelation.tail.name + 
                    "_" + snbRelation.name + 
                    "_" + snbRelation.head.name + 
                    "(_ridx)?" +
                    "_[0-9]+_[0-9]+\\.csv");
              }
            });

        if (fileList.length > 0) {
          for (File f : fileList) {
            loadList.add(new LoadUnit(snbRelation, f.toPath(),
                f.getName().matches(
                    "^" + snbRelation.tail.name + 
                    "_" + snbRelation.name + 
                    "_" + snbRelation.head.name + 
                    "_ridx" +
                    "_[0-9]+_[0-9]+\\.csv")));
            System.out.println(String.format("Found file for %s edges (%s)",
                edgeStr, f.getName()));

            totalEdgeFiles++;
          }
        } else {
          System.out.println(String.format("Missing file for %s edges",
              edgeStr));
        }
      }

      System.out.println(String.format("Found %d total edge files",
          totalEdgeFiles));
    }

    /*
     * Start the threads.
     */
    List<Thread> threads = new ArrayList<>();
    List<ThreadStats> threadStats = new ArrayList<>(numThreads);
    List<TorcGraph> threadGraphs = new ArrayList<>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      ThreadStats stats = new ThreadStats();

      // Create TorcGraph configuration.
      Map<String, String> torcConfig = new HashMap<>();
      torcConfig.put(TorcGraph.CONFIG_RC_IMAGE_CREATION_MODE, "yes");
      torcConfig.put(TorcGraph.CONFIG_RC_IMAGE_DIRECTORY, outputDir);
      torcConfig.put(TorcGraph.CONFIG_GRAPH_NAME, 
          String.format("part%04d.%s", loaderIdx * numThreads + i, 
            (String) opts.get("--graphName")));

      TorcGraph graph = TorcGraph.open(torcConfig);

      threads.add(new Thread(new LoaderThread(graph, loadList,
          numLoaders * numThreads, loaderIdx * numThreads + i, stats)));

      threads.get(i).start();

      threadStats.add(stats);
      threadGraphs.add(graph);
    }

    /*
     * Start stats reporting thread.
     */
    (new Thread(new StatsReporterThread(threadStats, reportInterval,
        formatString))).start();

    /*
     * Join on all the loader threads.
     */
    for (Thread thread : threads) {
      thread.join();
    }

    try {
      for (Graph graph : threadGraphs) {
        graph.close();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
