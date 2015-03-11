package com.logicblox.s3lib;

import com.google.common.base.Optional;

import java.io.File;

/**
 * {@code DownloadOptions} contains all the details needed by the download
 * operation. The specified {@code object}, under {@code bucket}, is downloaded
 * to a local {@code file}.
 * <p/>
 * If {@code recursive} is set, then all objects under {@code object} key will
 * be downloaded. Otherwise, only the first-level objects will be downloaded.
 * <p/>
 * If {@code overwrite} is set, then newly downloaded files is possible to
 * overwrite existing local files.
 * <p/>
 * If progress listener factories have been set, then progress notifications
 * will be recorded.
 * <p/>
 * {@code DownloadOptions} objects are meant to be built by {@code
 * DownloadOptionsBuilder}. This class provides only public getter methods.
 */
public class DownloadOptions {
    private File file;
    private String bucket;
    private String objectKey;
    private boolean recursive;
    private boolean overwrite;
    private Optional<S3ProgressListenerFactory> s3ProgressListenerFactory;
    private Optional<GCSProgressListenerFactory> gcsProgressListenerFactory;

    DownloadOptions(File file,
                    String bucket,
                    String objectKey,
                    boolean recursive,
                    boolean overwrite,
                    Optional<S3ProgressListenerFactory>
                        s3ProgressListenerFactory,
                    Optional<GCSProgressListenerFactory>
                        gcsProgressListenerFactory) {
        this.file = file;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.recursive = recursive;
        this.overwrite = overwrite;
        this.s3ProgressListenerFactory = s3ProgressListenerFactory;
        this.gcsProgressListenerFactory = gcsProgressListenerFactory;
    }

    public File getFile() {
        return file;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public boolean doesOverwrite() {
        return overwrite;
    }

    public Optional<S3ProgressListenerFactory> getS3ProgressListenerFactory() {
        return s3ProgressListenerFactory;
    }

    public Optional<GCSProgressListenerFactory> getGCSProgressListenerFactory() {
        return gcsProgressListenerFactory;
    }
}