/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.hadoop.variant;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.opencb.biodata.models.variant.VariantFileMetadata;
import org.opencb.commons.ProgressLogger;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.io.DataWriter;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.opencga.storage.core.StoragePipelineResult;
import org.opencb.opencga.storage.core.config.DatabaseCredentials;
import org.opencb.opencga.storage.core.config.StorageEtlConfiguration;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.exceptions.StoragePipelineException;
import org.opencb.opencga.storage.core.metadata.BatchFileOperation;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.StudyConfigurationManager;
import org.opencb.opencga.storage.core.utils.CellBaseUtils;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.VariantStoragePipeline;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryException;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.core.variant.annotation.VariantAnnotationManager;
import org.opencb.opencga.storage.core.variant.annotation.annotators.VariantAnnotator;
import org.opencb.opencga.storage.core.variant.io.VariantReaderUtils;
import org.opencb.opencga.storage.core.variant.stats.VariantStatisticsManager;
import org.opencb.opencga.storage.hadoop.auth.HBaseCredentials;
import org.opencb.opencga.storage.hadoop.utils.HBaseDataReader;
import org.opencb.opencga.storage.hadoop.utils.HBaseDataWriter;
import org.opencb.opencga.storage.hadoop.utils.HBaseManager;
import org.opencb.opencga.storage.hadoop.variant.adaptors.VariantHadoopDBAdaptor;
import org.opencb.opencga.storage.hadoop.variant.annotation.HadoopDefaultVariantAnnotationManager;
import org.opencb.opencga.storage.hadoop.variant.executors.ExternalMRExecutor;
import org.opencb.opencga.storage.hadoop.variant.executors.MRExecutor;
import org.opencb.opencga.storage.hadoop.variant.gaps.*;
import org.opencb.opencga.storage.hadoop.variant.index.VariantTableRemoveFileDriver;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixHelper;
import org.opencb.opencga.storage.hadoop.variant.metadata.HBaseStudyConfigurationDBAdaptor;
import org.opencb.opencga.storage.hadoop.variant.stats.HadoopDefaultVariantStatisticsManager;
import org.opencb.opencga.storage.hadoop.variant.stats.HadoopMRVariantStatisticsManager;
import org.opencb.opencga.storage.hadoop.variant.utils.HBaseVariantTableNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.opencb.opencga.storage.core.variant.VariantStorageEngine.Options.MERGE_MODE;
import static org.opencb.opencga.storage.core.variant.VariantStorageEngine.Options.RESUME;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.*;
import static org.opencb.opencga.storage.hadoop.variant.gaps.FillGapsDriver.FILL_GAPS_OPERATION_NAME;
import static org.opencb.opencga.storage.hadoop.variant.gaps.FillGapsDriver.FILL_MISSING_OPERATION_NAME;

/**
 * Created by mh719 on 16/06/15.
 */
public class HadoopVariantStorageEngine extends VariantStorageEngine {
    public static final String STORAGE_ENGINE_ID = "hadoop";

    public static final String HADOOP_BIN = "hadoop.bin";
    public static final String HADOOP_ENV = "hadoop.env";
    public static final String OPENCGA_STORAGE_HADOOP_JAR_WITH_DEPENDENCIES = "opencga.storage.hadoop.jar-with-dependencies";
    @Deprecated
    public static final String HADOOP_LOAD_ARCHIVE = "hadoop.load.archive";
    @Deprecated
    public static final String HADOOP_LOAD_VARIANT = "hadoop.load.variant";
    // Resume merge variants if the current status is RUNNING or DONE
    /**
     * @deprecated use {@link Options#RESUME}
     */
    @Deprecated
    public static final String HADOOP_LOAD_VARIANT_RESUME = "hadoop.load.variant.resume";
    // Merge variants operation status. Skip merge and run post-load/post-merge step if status is DONE
    public static final String HADOOP_LOAD_VARIANT_STATUS = "hadoop.load.variant.status";
    //Other files to be loaded from Archive to Variant
    public static final String HADOOP_LOAD_VARIANT_PENDING_FILES = "opencga.storage.hadoop.load.pending.files";
    public static final String INTERMEDIATE_HDFS_DIRECTORY = "opencga.storage.hadoop.intermediate.hdfs.directory";

    public static final String HADOOP_LOAD_ARCHIVE_BATCH_SIZE = "hadoop.load.archive.batch.size";
    public static final String HADOOP_LOAD_VARIANT_BATCH_SIZE = "hadoop.load.variant.batch.size";
    public static final String HADOOP_LOAD_DIRECT = "hadoop.load.direct";
    public static final boolean HADOOP_LOAD_DIRECT_DEFAULT = true;

    public static final String MERGE_ARCHIVE_SCAN_BATCH_SIZE = "opencga.storage.hadoop.hbase.merge.archive.scan.batchsize";
    public static final int DEFAULT_MERGE_ARCHIVE_SCAN_BATCH_SIZE = 500;
    public static final String MERGE_COLLAPSE_DELETIONS      = "opencga.storage.hadoop.hbase.merge.collapse-deletions";
    public static final boolean DEFAULT_MERGE_COLLAPSE_DELETIONS = false;
    public static final String MERGE_LOAD_SPECIFIC_PUT       = "opencga.storage.hadoop.hbase.merge.use_specific_put";
//    public static final String MERGE_LOAD_STUDY_COLUMNS      = "opencga.storage.hadoop.hbase.merge.study_columns";
    public static final String MERGE_LOAD_SAMPLE_COLUMNS     = "opencga.storage.hadoop.hbase.merge.sample_columns";
    public static final boolean DEFAULT_MERGE_LOAD_SAMPLE_COLUMNS = true;

    //upload HBase jars and jars for any of the configured job classes via the distributed cache (tmpjars).
    public static final String MAPREDUCE_ADD_DEPENDENCY_JARS = "opencga.mapreduce.addDependencyJars";
    public static final String MAPREDUCE_HBASE_SCANNER_TIMEOUT = "opencga.storage.hadoop.mapreduce.scanner.timeout";
    public static final String MAPREDUCE_HBASE_KEYVALUE_SIZE_MAX = "hadoop.load.variant.hbase.client.keyvalue.maxsize";
    public static final String MAPREDUCE_HBASE_SCAN_CACHING = "hadoop.load.variant.scan.caching";

    public static final String HBASE_NAMESPACE = "opencga.storage.hadoop.variant.hbase.namespace";
    public static final String HBASE_COLUMN_FAMILY = "opencga.hbase.column_family";
    public static final String EXPECTED_FILES_NUMBER = "expected_files_number";
    public static final int DEFAULT_EXPECTED_FILES_NUMBER = 5000;

    // Variant table configuration
    public static final String VARIANT_TABLE_COMPRESSION = "opencga.variant.table.compression";
    public static final String VARIANT_TABLE_PRESPLIT_SIZE = "opencga.variant.table.presplit.size";
    // Do not create phoenix indexes. Testing purposes only
    public static final String VARIANT_TABLE_INDEXES_SKIP = "opencga.variant.table.indexes.skip";

    // Archive table configuration
    public static final String ARCHIVE_TABLE_COMPRESSION = "opencga.archive.table.compression";
    public static final String ARCHIVE_TABLE_PRESPLIT_SIZE = "opencga.archive.table.presplit.size";
    public static final int DEFAULT_ARCHIVE_TABLE_PRESPLIT_SIZE = 100;
    public static final String ARCHIVE_CHUNK_SIZE = "opencga.archive.chunk_size";
    public static final int DEFAULT_ARCHIVE_CHUNK_SIZE = 1000;
    public static final String ARCHIVE_ROW_KEY_SEPARATOR = "opencga.archive.row_key_sep";
    public static final String ARCHIVE_FILE_BATCH_SIZE = "opencga.archive.file_batch_size";
    public static final int DEFAULT_ARCHIVE_FILE_BATCH_SIZE = 1000;

    public static final String EXTERNAL_MR_EXECUTOR = "opencga.external.mr.executor";
    public static final String STATS_LOCAL = "stats.local";

    public static final String DBADAPTOR_PHOENIX_FETCH_SIZE = "dbadaptor.phoenix.fetch_size";
    public static final String MISSING_GENOTYPES_UPDATED = "missing_genotypes_updated";
    public static final int FILL_GAPS_MAX_SAMPLES = 100;

    protected Configuration conf = null;
    protected MRExecutor mrExecutor;
    private HdfsVariantReaderUtils variantReaderUtils;
    private HBaseManager hBaseManager;
    private final AtomicReference<VariantHadoopDBAdaptor> dbAdaptor = new AtomicReference<>();
    private Logger logger = LoggerFactory.getLogger(HadoopVariantStorageEngine.class);
    private HBaseVariantTableNameGenerator tableNameGenerator;

    public HadoopVariantStorageEngine() {
//        variantReaderUtils = new HdfsVariantReaderUtils(conf);
    }

    @Override
    public List<StoragePipelineResult> index(List<URI> inputFiles, URI outdirUri, boolean doExtract, boolean doTransform, boolean doLoad)
            throws StorageEngineException {

        if (inputFiles.size() == 1 || !doLoad) {
            return super.index(inputFiles, outdirUri, doExtract, doTransform, doLoad);
        }

        final boolean doArchive;
        final boolean doMerge;


        if (!getOptions().containsKey(HADOOP_LOAD_ARCHIVE) && !getOptions().containsKey(HADOOP_LOAD_VARIANT)) {
            doArchive = true;
            doMerge = true;
        } else {
            doArchive = getOptions().getBoolean(HADOOP_LOAD_ARCHIVE, false);
            doMerge = getOptions().getBoolean(HADOOP_LOAD_VARIANT, false);
        }

        if (!doArchive && !doMerge) {
            return Collections.emptyList();
        }

        final int nThreadArchive = getOptions().getInt(HADOOP_LOAD_ARCHIVE_BATCH_SIZE, 2);
        ObjectMap extraOptions = new ObjectMap()
                .append(HADOOP_LOAD_ARCHIVE, true)
                .append(HADOOP_LOAD_VARIANT, false);

        final List<StoragePipelineResult> concurrResult = new CopyOnWriteArrayList<>();
        List<VariantStoragePipeline> etlList = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(
                nThreadArchive,
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                }); // Set Daemon for quick shutdown !!!
        LinkedList<Future<StoragePipelineResult>> futures = new LinkedList<>();
        List<Integer> indexedFiles = new CopyOnWriteArrayList<>();
        AtomicBoolean continueLoading = new AtomicBoolean(true);
        for (URI inputFile : inputFiles) {
            //Provide a connected storageETL if load is required.

            VariantStoragePipeline storageETL = newStoragePipeline(doLoad, new ObjectMap(extraOptions));
            futures.add(executorService.submit(() -> {
                if (!continueLoading.get()) {
                    return null;
                }
                try {
                    Thread.currentThread().setName(Paths.get(inputFile).getFileName().toString());
                    StoragePipelineResult storagePipelineResult = new StoragePipelineResult(inputFile);
                    URI nextUri = inputFile;
                    boolean error = false;
                    if (doTransform) {
                        try {
                            nextUri = transformFile(storageETL, storagePipelineResult, concurrResult, nextUri, outdirUri);

                        } catch (StoragePipelineException ignore) {
                            //Ignore here. Errors are stored in the ETLResult
                            error = true;
                        }
                    }

                    if (doLoad && doArchive && !error) {
                        try {
                            loadFile(storageETL, storagePipelineResult, concurrResult, nextUri, outdirUri);
                        } catch (StoragePipelineException ignore) {
                            //Ignore here. Errors are stored in the ETLResult
                            error = true;
                        }
                    }
                    if (doLoad && !error) {
                        // Read the VariantSource to get the original fileName (it may be different from the
                        // nextUri.getFileName if this is the transformed file)
                        String fileName = storageETL.readVariantFileMetadata(nextUri, null).getPath();
                        // Get latest study configuration from DB, might have been changed since
                        StudyConfiguration studyConfiguration = storageETL.getStudyConfiguration();
                        // Get file ID for the provided file name
                        Integer fileId = studyConfiguration.getFileIds().get(fileName);
                        indexedFiles.add(fileId);
                    }
                    return storagePipelineResult;
                } finally {
                    try {
                        storageETL.close();
                    } catch (StorageEngineException e) {
                        logger.error("Issue closing DB connection ", e);
                    }
                }
            }));
        }

        executorService.shutdown();

        int errors = 0;
        try {
            while (!futures.isEmpty()) {
                // Check values
                if (futures.peek().isDone() || futures.peek().isCancelled()) {
                    Future<StoragePipelineResult> first = futures.pop();
                    StoragePipelineResult result = first.get(1, TimeUnit.MINUTES);
                    if (result == null) {
                        continue;
                    }
                    boolean error = false;
                    if (result.getTransformError() != null) {
                        logger.error("Error transforming file " + result.getInput(), result.getTransformError());
                        error = true;
                    } else if (result.getLoadError() != null) {
                        error = true;
                        logger.error("Error loading file " + result.getInput(), result.getLoadError());
                    }
                    if (error) {
                        //TODO: Handle errors. Retry?
                        errors++;
                        if (getOptions().getBoolean("abortOnError", false)) {
                            continueLoading.set(false);
                        }
                    }
                    concurrResult.add(result);
                } else {
                    // Sleep only if the task is not done
                    executorService.awaitTermination(1, TimeUnit.MINUTES);
                }
            }
            if (errors > 0) {
                throw new StoragePipelineException("Errors found", concurrResult);
            }

            if (doLoad && doMerge) {
                int batchMergeSize = getOptions().getInt(HADOOP_LOAD_VARIANT_BATCH_SIZE, 10);
                // Overwrite default ID list with user provided IDs
                List<Integer> pendingFiles = indexedFiles;
                if (getOptions().containsKey(HADOOP_LOAD_VARIANT_PENDING_FILES)) {
                    List<Integer> idList = getOptions().getAsIntegerList(HADOOP_LOAD_VARIANT_PENDING_FILES);
                    if (!idList.isEmpty()) {
                        // only if the list is not empty
                        pendingFiles = idList;
                    }
                }

                List<Integer> filesToMerge = new ArrayList<>(batchMergeSize);
                int i = 0;
                for (Iterator<Integer> iterator = pendingFiles.iterator(); iterator.hasNext(); i++) {
                    Integer indexedFile = iterator.next();
                    filesToMerge.add(indexedFile);
                    if (filesToMerge.size() == batchMergeSize || !iterator.hasNext()) {
                        extraOptions = new ObjectMap()
                                .append(HADOOP_LOAD_ARCHIVE, false)
                                .append(HADOOP_LOAD_VARIANT, true)
                                .append(HADOOP_LOAD_VARIANT_PENDING_FILES, filesToMerge);
                        AbstractHadoopVariantStoragePipeline localEtl = newStoragePipeline(doLoad, extraOptions);

                        int studyId = getOptions().getInt(Options.STUDY_ID.key());
                        URI input = concurrResult.get(i).getPostTransformResult();
                        if (input == null) {
                            input = inputFiles.get(i);
                        }
                        localEtl.preMerge(input);
                        localEtl.merge(studyId, filesToMerge);
                        localEtl.postMerge(input, outdirUri);
                        filesToMerge.clear();
                    }
                }

                annotateLoadedFiles(outdirUri, inputFiles, concurrResult, getOptions());
                calculateStatsForLoadedFiles(outdirUri, inputFiles, concurrResult, getOptions());

            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoragePipelineException("Interrupted!", e, concurrResult);
        } catch (ExecutionException e) {
            throw new StoragePipelineException("Execution exception!", e, concurrResult);
        } catch (TimeoutException e) {
            throw new StoragePipelineException("Timeout Exception", e, concurrResult);
        } finally {
            if (!executorService.isShutdown()) {
                try {
                    executorService.shutdownNow();
                } catch (Exception e) {
                    logger.error("Problems shutting executer service down", e);
                }
            }
        }
        return concurrResult;
    }

    @Override
    public AbstractHadoopVariantStoragePipeline newStoragePipeline(boolean connected) throws StorageEngineException {
        return newStoragePipeline(connected, null);
    }

    @Override
    protected VariantAnnotationManager newVariantAnnotationManager(VariantAnnotator annotator) throws StorageEngineException {
        return new HadoopDefaultVariantAnnotationManager(annotator, getDBAdaptor());
    }

    @Override
    public VariantStatisticsManager newVariantStatisticsManager() throws StorageEngineException {
        // By default, execute a MR to calculate statistics
        if (getOptions().getBoolean(STATS_LOCAL, false)) {
            return new HadoopDefaultVariantStatisticsManager(getDBAdaptor());
        } else {
            return new HadoopMRVariantStatisticsManager(getDBAdaptor(), getMRExecutor(getOptions()), getOptions());
        }
    }

    @Override
    public void fillMissing(String study, ObjectMap options) throws StorageEngineException {
        logger.info("FillMissing: Study " + study);

        StudyConfigurationManager scm = getStudyConfigurationManager();
        StudyConfiguration studyConfiguration = scm.getStudyConfiguration(study, null).first();

        fillGapsOrMissing(study, studyConfiguration, studyConfiguration.getIndexedFiles(), Collections.emptyList(), false, options);
    }

    @Override
    public void fillGaps(String study, List<String> samples, ObjectMap options) throws StorageEngineException {
        if (samples == null || samples.size() < 2) {
            throw new IllegalArgumentException("Fill gaps operation requires at least two samples.");
        } else if (samples.size() > FILL_GAPS_MAX_SAMPLES) {
            throw new IllegalArgumentException("Unable to execute fill gaps operation with more than "
                    + FILL_GAPS_MAX_SAMPLES + " samples.");
        }

        StudyConfigurationManager scm = getStudyConfigurationManager();
        StudyConfiguration studyConfiguration = scm.getStudyConfiguration(study, null).first();
        List<Integer> sampleIds = new ArrayList<>(samples.size());
        for (String sample : samples) {
            Integer sampleId = StudyConfigurationManager.getSampleIdFromStudy(sample, studyConfiguration);
            if (sampleId != null) {
                sampleIds.add(sampleId);
            } else {
                throw VariantQueryException.sampleNotFound(sample, studyConfiguration.getStudyName());
            }
        }

        // Get files
        Set<Integer> fileIds = new HashSet<>();
        for (Map.Entry<Integer, LinkedHashSet<Integer>> entry : studyConfiguration.getSamplesInFiles().entrySet()) {
            if (studyConfiguration.getIndexedFiles().contains(entry.getKey()) && !Collections.disjoint(entry.getValue(), sampleIds)) {
                fileIds.add(entry.getKey());
            }
        }

        logger.info("FillGaps: Study " + study + ", samples " + samples);
        fillGapsOrMissing(study, studyConfiguration, fileIds, sampleIds, true, options);
    }

    private void fillGapsOrMissing(String study, StudyConfiguration studyConfiguration, Set<Integer> fileIds, List<Integer> sampleIds,
                                   boolean fillGaps, ObjectMap inputOptions) throws StorageEngineException {
        ObjectMap options = new ObjectMap(getOptions());
        if (inputOptions != null) {
            options.putAll(inputOptions);
        }

        StudyConfigurationManager scm = getStudyConfigurationManager();
        int studyId = studyConfiguration.getStudyId();

        String jobOperationName = fillGaps ? FILL_GAPS_OPERATION_NAME : FILL_MISSING_OPERATION_NAME;
        List<Integer> fileIdsList = new ArrayList<>(fileIds);
        fileIdsList.sort(Integer::compareTo);

        scm.lockAndUpdate(study, sc -> {
            boolean resume = options.getBoolean(RESUME.key(), RESUME.defaultValue());
            StudyConfigurationManager.addBatchOperation(
                    sc,
                    jobOperationName,
                    fileIdsList,
                    resume,
                    BatchFileOperation.Type.OTHER,
                    // Allow concurrent operations if fillGaps.
                    (v) -> fillGaps || v.getOperationName().equals(FILL_GAPS_OPERATION_NAME));
            return sc;
        });

        Thread hook = scm.buildShutdownHook(FILL_GAPS_OPERATION_NAME, studyId, Collections.emptyList());
        Exception exception = null;
        try {
            Runtime.getRuntime().addShutdownHook(hook);

            if (options.getBoolean("local", false)) {
                ProgressLogger progressLogger = new ProgressLogger("Process");
                VariantHadoopDBAdaptor dbAdaptor = getDBAdaptor();
                Scan scan;
                if (fillGaps) {
                    scan = FillGapsFromArchiveTask.buildScan(
                            fileIds,
                            options.getString(VariantQueryParam.REGION.key()), dbAdaptor.getConfiguration());
                } else {
                    scan = FillMissingFromArchiveTask.buildScan(
                            fileIds,
                            options.getString(VariantQueryParam.REGION.key()), dbAdaptor.getConfiguration());
                }
                logger.info("Scan archive table " + getArchiveTableName(studyId) + " with scan " + scan.toString(50));
                HBaseDataReader dbReader = new HBaseDataReader(dbAdaptor.getHBaseManager(), getArchiveTableName(studyId), scan);
                DataWriter<Put> writer = new HBaseDataWriter<>(dbAdaptor.getHBaseManager(), getVariantTableName());
                ParallelTaskRunner.Config config = ParallelTaskRunner.Config.builder().setNumTasks(4).setBatchSize(10).build();
                List<AbstractFillFromArchiveTask> tasks = new ArrayList<>();
                ParallelTaskRunner<Result, Put> ptr = new ParallelTaskRunner<>(
                        dbReader,
                        () -> {
                            AbstractFillFromArchiveTask task;
                            if (fillGaps) {
                                task = new FillGapsFromArchiveTask(dbAdaptor.getHBaseManager(),
                                        getArchiveTableName(studyId), studyConfiguration,
                                        dbAdaptor.getGenomeHelper(), sampleIds);
                            } else {
                                task = new FillMissingFromArchiveTask(dbAdaptor.getHBaseManager(), studyConfiguration,
                                        dbAdaptor.getGenomeHelper());
                            }
                            tasks.add(task);
                            return task
                                    .then((ParallelTaskRunner.TaskWithException<Put, Put, IOException>) list -> {
                                        progressLogger.increment(list.size(), "variants");
                                        return list;
                                    });
                        },
                        writer,
                        config);
                ptr.run();
                Map<String, Long> stats = tasks.stream()
                        .map(AbstractFillFromArchiveTask::takeStats)
                        .flatMap(map -> map.entrySet().stream())
                        .collect(Collectors.groupingBy(
                                Map.Entry::getKey,
                                TreeMap::new,
                                Collectors.reducing(0L, Map.Entry::getValue, Long::sum)));
                logger.info(jobOperationName + " stats:");
                stats.entrySet().stream()
                        .map(entry -> {
                            if (entry.getKey().contains("TIME_NS")) {
                                return ImmutablePair.of(
                                        StringUtils.replace(entry.getKey(), "TIME_NS", "TIME_MS"),
                                        TimeUnit.NANOSECONDS.toMillis(entry.getValue()));
                            } else {
                                return entry;
                            }
                        })
                        .forEach((entry) -> logger.info('\t' + entry.getKey() + " = " + entry.getValue()));
            } else {
                String hadoopRoute = options.getString(HADOOP_BIN, "hadoop");
                String jar = getJarWithDependencies(options);

                options.put(FillGapsFromArchiveMapper.SAMPLES, sampleIds);
                options.put(FillGapsFromArchiveMapper.FILL_GAPS, fillGaps);

                Class execClass = FillGapsDriver.class;
                String executable = hadoopRoute + " jar " + jar + ' ' + execClass.getName();
                String args = FillGapsDriver.buildCommandLineArgs(
                        getArchiveTableName(studyId),
                        getVariantTableName(),
                        studyId, fileIds, options);

                long startTime = System.currentTimeMillis();
                logger.info("------------------------------------------------------");
                logger.info("Fill gaps of samples {} into variants table '{}'",
                        fillGaps ? sampleIds.toString() : "\"ALL\"", getVariantTableName());
                logger.debug(executable + ' ' + args);
                logger.info("------------------------------------------------------");
                int exitValue = getMRExecutor(options).run(executable, args);
                logger.info("------------------------------------------------------");
                logger.info("Exit value: {}", exitValue);
                logger.info("Total time: {}s", (System.currentTimeMillis() - startTime) / 1000.0);
                if (exitValue != 0) {
                    throw new StorageEngineException("Error filling gaps for samples " + sampleIds);
                }
            }

        } catch (RuntimeException | ExecutionException e) {
            exception = e;
            throw new StorageEngineException("Error filling gaps for samples " + sampleIds, e);
        } finally {
            boolean fail = exception != null;
            scm.lockAndUpdate(study, sc -> {
                StudyConfigurationManager.setStatus(sc,
                        fail ? BatchFileOperation.Status.ERROR : BatchFileOperation.Status.READY,
                        jobOperationName, fileIdsList);
                if (!fillGaps && StringUtils.isEmpty(options.getString(VariantQueryParam.REGION.key()))) {
                    sc.getAttributes().put(MISSING_GENOTYPES_UPDATED, !fail);
                }
                return sc;
            });
            Runtime.getRuntime().removeShutdownHook(hook);
        }

    }

    public AbstractHadoopVariantStoragePipeline newStoragePipeline(boolean connected, Map<? extends String, ?> extraOptions)
            throws StorageEngineException {
        ObjectMap options = new ObjectMap(configuration.getStorageEngine(STORAGE_ENGINE_ID).getVariant().getOptions());
        if (extraOptions != null) {
            options.putAll(extraOptions);
        }
        boolean directLoad = options.getBoolean(HADOOP_LOAD_DIRECT, HADOOP_LOAD_DIRECT_DEFAULT);
        VariantHadoopDBAdaptor dbAdaptor = connected ? getDBAdaptor() : null;
        Configuration hadoopConfiguration = null == dbAdaptor ? null : dbAdaptor.getConfiguration();
        hadoopConfiguration = hadoopConfiguration == null ? getHadoopConfiguration(options) : hadoopConfiguration;
        hadoopConfiguration.setIfUnset(ARCHIVE_TABLE_COMPRESSION, Algorithm.SNAPPY.getName());

        int studyId = options.getInt(Options.STUDY_ID.key());
        HBaseCredentials archiveCredentials = buildCredentials(getArchiveTableName(studyId));
        MergeMode mergeMode;
        if (connected) {
            StudyConfiguration sc = getStudyConfigurationManager().getStudyConfiguration(studyId, null).first();
            if (sc == null || !sc.getAttributes().containsKey(MERGE_MODE.key())) {
                mergeMode = MergeMode.from(options);
            } else {
                mergeMode = MergeMode.from(sc.getAttributes());
            }
        } else {
            mergeMode = MergeMode.from(options);
        }

        AbstractHadoopVariantStoragePipeline storageETL;
        if (mergeMode.equals(MergeMode.BASIC)) {
            storageETL = new HadoopMergeBasicVariantStoragePipeline(configuration, dbAdaptor,
                    hadoopConfiguration, archiveCredentials, getVariantReaderUtils(hadoopConfiguration), options);
        } else if (directLoad) {
            storageETL = new HadoopDirectVariantStoragePipeline(configuration, dbAdaptor, getMRExecutor(options),
                    hadoopConfiguration, archiveCredentials, getVariantReaderUtils(hadoopConfiguration), options);
        } else {
            storageETL = new HadoopVariantStoragePipeline(configuration, dbAdaptor, getMRExecutor(options),
                    hadoopConfiguration, archiveCredentials, getVariantReaderUtils(hadoopConfiguration), options);
        }
        return storageETL;
    }

    public HdfsVariantReaderUtils getVariantReaderUtils() {
        return getVariantReaderUtils(conf);
    }

    private HdfsVariantReaderUtils getVariantReaderUtils(Configuration config) {
        if (null == variantReaderUtils) {
            variantReaderUtils = new HdfsVariantReaderUtils(config);
        } else if (this.variantReaderUtils.conf == null && config != null) {
            variantReaderUtils = new HdfsVariantReaderUtils(config);
        }
        return variantReaderUtils;
    }

    @Override
    public void removeFiles(String study, List<String> files) throws StorageEngineException {
        ObjectMap options = configuration.getStorageEngine(STORAGE_ENGINE_ID).getVariant().getOptions();

        VariantDBAdaptor dbAdaptor = getDBAdaptor();
        StudyConfigurationManager scm = dbAdaptor.getStudyConfigurationManager();
        List<Integer> fileIds = preRemoveFiles(study, files);
        final int studyId = scm.getStudyId(study, null);

//        // Pre delete
//        scm.lockAndUpdate(studyId, sc -> {
//            if (!sc.getIndexedFiles().contains(fileId)) {
//                throw StorageEngineException.unableToExecute("File not indexed.", fileId, sc);
//            }
//            boolean resume = options.getBoolean(Options.RESUME.key(), Options.RESUME.defaultValue())
//                    || options.getBoolean(HadoopVariantStorageEngine.HADOOP_LOAD_VARIANT_RESUME, false);
//            BatchFileOperation operation =
//                    etl.addBatchOperation(sc, VariantTableRemoveFileDriver.JOB_OPERATION_NAME, fileList, resume,
//                            BatchFileOperation.Type.REMOVE);
//            options.put(AbstractAnalysisTableDriver.TIMESTAMP, operation.getTimestamp());
//            return sc;
//        });

        StudyConfiguration sc = scm.getStudyConfiguration(studyId, null).first();
        BatchFileOperation operation = StudyConfigurationManager.getOperation(sc, REMOVE_OPERATION_NAME, fileIds);
        options.put(AbstractAnalysisTableDriver.TIMESTAMP, operation.getTimestamp());

        // Delete
        Thread hook = getStudyConfigurationManager().buildShutdownHook(REMOVE_OPERATION_NAME, studyId, fileIds);
        try {
            Runtime.getRuntime().addShutdownHook(hook);

            String archiveTable = getArchiveTableName(studyId);
            HBaseCredentials variantsTable = getDbCredentials();
            String hadoopRoute = options.getString(HADOOP_BIN, "hadoop");
            String jar = getJarWithDependencies(options);

            Class execClass = VariantTableRemoveFileDriver.class;
            String args = VariantTableRemoveFileDriver.buildCommandLineArgs(archiveTable,
                    variantsTable.getTable(), studyId, fileIds, options);
            String executable = hadoopRoute + " jar " + jar + ' ' + execClass.getName();

            long startTime = System.currentTimeMillis();
            logger.info("------------------------------------------------------");
            logger.info("Remove files {} in archive '{}' and analysis table '{}'", fileIds, archiveTable, variantsTable.getTable());
            logger.debug(executable + " " + args);
            logger.info("------------------------------------------------------");
            int exitValue = getMRExecutor(options).run(executable, args);
            logger.info("------------------------------------------------------");
            logger.info("Exit value: {}", exitValue);
            logger.info("Total time: {}s", (System.currentTimeMillis() - startTime) / 1000.0);
            if (exitValue != 0) {
                throw new StorageEngineException("Error removing files " + fileIds + " from tables ");
            }

//            // Post Delete
//            // If everything went fine, remove file column from Archive table and from studyconfig
//            scm.lockAndUpdate(studyId, sc -> {
//                scm.setStatus(sc, BatchFileOperation.Status.READY,
//                        VariantTableRemoveFileDriver.JOB_OPERATION_NAME, fileIds);
//                sc.getIndexedFiles().remove(fileId);
//                return sc;
//            });

            postRemoveFiles(study, fileIds, false);
        } catch (Exception e) {
            postRemoveFiles(study, fileIds, true);
            throw e;
        } finally {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    @Override
    protected void postRemoveFiles(String study, List<Integer> fileIds, boolean error) throws StorageEngineException {
        super.postRemoveFiles(study, fileIds, error);
        if (!error) {
            VariantHadoopDBAdaptor dbAdaptor = getDBAdaptor();
            VariantPhoenixHelper phoenixHelper = new VariantPhoenixHelper(dbAdaptor.getGenomeHelper());

            StudyConfiguration sc = dbAdaptor.getStudyConfigurationManager().getStudyConfiguration(study, null).first();

            List<Integer> sampleIds = new ArrayList<>();
            for (Integer fileId : fileIds) {
                sampleIds.addAll(sc.getSamplesInFiles().get(fileId));
            }

            try {
                phoenixHelper.dropFiles(dbAdaptor.getJdbcConnection(), dbAdaptor.getVariantTable(), sc.getStudyId(), fileIds, sampleIds);
            } catch (SQLException e) {
                throw new StorageEngineException("Error removing columns from Phoenix", e);
            }
        }
    }

    @Override
    public void removeStudy(String studyName) throws StorageEngineException {
        throw new UnsupportedOperationException("Unimplemented");
    }

    private HBaseCredentials getDbCredentials() throws StorageEngineException {
        String table = getVariantTableName();
        return buildCredentials(table);
    }

    @Override
    public VariantHadoopDBAdaptor getDBAdaptor() throws StorageEngineException {
        if (dbAdaptor.get() == null) {
            synchronized (dbAdaptor) {
                if (dbAdaptor.get() == null) {
                    HBaseCredentials credentials = getDbCredentials();
                    try {
                        Configuration configuration = getHadoopConfiguration();
                        configuration = VariantHadoopDBAdaptor.getHbaseConfiguration(configuration, credentials);
                        dbAdaptor.set(new VariantHadoopDBAdaptor(getHBaseManager(configuration), credentials,
                                this.configuration, configuration, getCellBaseUtils(), getTableNameGenerator()));
                    } catch (IOException e) {
                        throw new StorageEngineException("Error creating DB Adapter", e);
                    }
                }
            }
        }
        return dbAdaptor.get();
    }

    private synchronized HBaseManager getHBaseManager(Configuration configuration) {
        if (hBaseManager == null) {
            hBaseManager = new HBaseManager(configuration);
        }
        return hBaseManager;
    }

    @Override
    protected boolean doQuerySearchManager(Query query, QueryOptions options) throws StorageEngineException {
        // TODO: Query using SearchManager even if FILES filter is used
        return super.doQuerySearchManager(query, options);
    }

    @Override
    public Query preProcessQuery(Query originalQuery, StudyConfigurationManager studyConfigurationManager) throws StorageEngineException {
        // Copy input query! Do not modify original query!
        Query query = originalQuery == null ? new Query() : new Query(originalQuery);
        List<String> studyNames = studyConfigurationManager.getStudyNames(QueryOptions.empty());
        CellBaseUtils cellBaseUtils = getCellBaseUtils();

        if (isValidParam(query, VariantQueryParam.STUDY) && studyNames.size() == 1) {
            query.remove(VariantQueryParam.STUDY.key());
        }

        convertGoToGeneQuery(query, cellBaseUtils);
        convertExpressionToGeneQuery(query, cellBaseUtils);

        return query;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (hBaseManager != null) {
            hBaseManager.close();
            hBaseManager = null;
        }
        if (dbAdaptor.get() != null) {
            dbAdaptor.get().close();
            dbAdaptor.set(null);
        }
    }

    private HBaseCredentials buildCredentials(String table) throws StorageEngineException {
        StorageEtlConfiguration vStore = configuration.getStorageEngine(STORAGE_ENGINE_ID).getVariant();

        DatabaseCredentials db = vStore.getDatabase();
        String user = db.getUser();
        String pass = db.getPassword();
        List<String> hostList = db.getHosts();
        if (hostList != null && hostList.size() > 1) {
            throw new IllegalStateException("Expect only one server name");
        }
        String target = hostList != null && !hostList.isEmpty() ? hostList.get(0) : null;
        try {
            String server;
            Integer port;
            String zookeeperPath;
            if (target == null || target.isEmpty()) {
                Configuration conf = getHadoopConfiguration();
                server = conf.get(HConstants.ZOOKEEPER_QUORUM);
                port = 60000;
                zookeeperPath = conf.get(HConstants.ZOOKEEPER_ZNODE_PARENT);
            } else {
                URI uri;
                try {
                    uri = new URI(target);
                } catch (URISyntaxException e) {
                    try {
                        uri = new URI("hbase://" + target);
                    } catch (URISyntaxException e1) {
                        throw e;
                    }
                }
                server = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : 60000;
                // If just an IP or host name is provided, the URI parser will return empty host, and the content as "path". Avoid that
                if (server == null) {
                    server = uri.getPath();
                    zookeeperPath = null;
                } else {
                    zookeeperPath = uri.getPath();
                }
            }
            HBaseCredentials credentials;
            if (!StringUtils.isBlank(zookeeperPath)) {
                credentials = new HBaseCredentials(server, table, user, pass, port, zookeeperPath);
            } else {
                credentials = new HBaseCredentials(server, table, user, pass, port);
            }
            return credentials;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }


    @Override
    public StudyConfigurationManager getStudyConfigurationManager() throws StorageEngineException {
        ObjectMap options = getOptions();
        HBaseCredentials dbCredentials = getDbCredentials();
        Configuration configuration = VariantHadoopDBAdaptor.getHbaseConfiguration(getHadoopConfiguration(), dbCredentials);
        return new StudyConfigurationManager(
                new HBaseStudyConfigurationDBAdaptor(
                        getTableNameGenerator().getMetaTableName(), configuration, getHBaseManager(configuration)));
    }

    private Configuration getHadoopConfiguration() throws StorageEngineException {
        return getHadoopConfiguration(getOptions());
    }

    private Configuration getHadoopConfiguration(ObjectMap options) throws StorageEngineException {
        Configuration conf = this.conf == null ? HBaseConfiguration.create() : this.conf;
        // This is the only key needed to connect to HDFS:
        //   CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY = fs.defaultFS
        //

        if (conf.get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY) == null) {
            throw new StorageEngineException("Missing configuration parameter \""
                    + CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY + "\"");
        }

        options.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .forEach(entry -> conf.set(entry.getKey(), options.getString(entry.getKey())));
        return conf;
    }

    private MRExecutor getMRExecutor(ObjectMap options) {
        if (options.containsKey(EXTERNAL_MR_EXECUTOR)) {
            Class<? extends MRExecutor> aClass;
            if (options.get(EXTERNAL_MR_EXECUTOR) instanceof Class) {
                aClass = options.get(EXTERNAL_MR_EXECUTOR, Class.class).asSubclass(MRExecutor.class);
            } else {
                try {
                    aClass = Class.forName(options.getString(EXTERNAL_MR_EXECUTOR)).asSubclass(MRExecutor.class);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            try {
                return aClass.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        } else if (mrExecutor == null) {
            return new ExternalMRExecutor(options);
        } else {
            return mrExecutor;
        }
    }

    /**
     * Get the archive table name given a StudyId.
     *
     * @param studyId Numerical study identifier
     * @return Table name
     */
    public String getArchiveTableName(int studyId) {
        return getTableNameGenerator().getArchiveTableName(studyId);
    }

    public String getVariantTableName() {
        return getTableNameGenerator().getVariantTableName();
    }

    private HBaseVariantTableNameGenerator getTableNameGenerator() {
        if (tableNameGenerator == null) {
            tableNameGenerator = new HBaseVariantTableNameGenerator(dbName, getOptions());
        }
        return tableNameGenerator;
    }

    public static String getJarWithDependencies(ObjectMap options) throws StorageEngineException {
        String jar = options.getString(OPENCGA_STORAGE_HADOOP_JAR_WITH_DEPENDENCIES, null);
        if (jar == null) {
            throw new StorageEngineException("Missing option " + OPENCGA_STORAGE_HADOOP_JAR_WITH_DEPENDENCIES);
        }
        if (!Paths.get(jar).isAbsolute()) {
            jar = System.getProperty("app.home", "") + "/" + jar;
        }
        return jar;
    }

    public VariantFileMetadata readVariantFileMetadata(URI input) throws StorageEngineException {
        return getVariantReaderUtils(null).readVariantFileMetadata(input);
    }

    private static class HdfsVariantReaderUtils extends VariantReaderUtils {
        private final Configuration conf;

        HdfsVariantReaderUtils(Configuration conf) {
            this.conf = conf;
        }

        @Override
        public VariantFileMetadata readVariantFileMetadata(URI input) throws StorageEngineException {
            VariantFileMetadata source;

            if (input.getScheme() == null || input.getScheme().startsWith("file")) {
                if (input.getPath().contains("variants.proto")) {
                    return VariantReaderUtils.readVariantFileMetadata(Paths.get(input.getPath()
                            .replace("variants.proto", "file.json")), null);
                } else {
                    return VariantReaderUtils.readVariantFileMetadata(Paths.get(input.getPath()), null);
                }
            }

            Path metaPath = new Path(VariantReaderUtils.getMetaFromTransformedFile(input.toString()));
            FileSystem fs = null;
            try {
                fs = FileSystem.get(conf);
            } catch (IOException e) {
                throw new StorageEngineException("Unable to get FileSystem", e);
            }
            try (
                    InputStream inputStream = new GZIPInputStream(fs.open(metaPath))
            ) {
                source = VariantReaderUtils.readVariantFileMetadataFromJson(inputStream);
            } catch (IOException e) {
                throw new StorageEngineException("Unable to read VariantFileMetadata", e);
            }
            return source;
        }
    }
}
