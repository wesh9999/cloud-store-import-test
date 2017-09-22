/*
  Copyright 2017, Infor Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package com.logicblox.cloudstore;

import java.io.File;


/**
 * {@code UploadOptionsBuilder} is a builder for {@code UploadOptions} objects.
 * <p>
 * Setting fields {@code file}, {@code bucket} and {@code objectKey} is
 * mandatory. All the others are optional.
 */
public class UploadOptionsBuilder extends CommandOptionsBuilder {
    private File file;
    private String bucket;
    private String objectKey;
    private long chunkSize = -1;
    private String encKey;
    private String cannedAcl;
    private OverallProgressListenerFactory overallProgressListenerFactory;
    private boolean dryRun = false;
    private boolean ignoreAbortInjection = false;

    UploadOptionsBuilder(CloudStoreClient client) {
        _cloudStoreClient = client;
     }

    public UploadOptionsBuilder setFile(File file) {
        this.file = file;
        return this;
    }

    public UploadOptionsBuilder setBucketName(String bucket) {
        this.bucket = bucket;
        return this;
    }

    public UploadOptionsBuilder setObjectKey(String objectKey) {
        this.objectKey = objectKey;
        return this;
    }

    public UploadOptionsBuilder setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
        return this;
    }

    public UploadOptionsBuilder setEncKey(String encKey) {
        this.encKey = encKey;
        return this;
    }

    public UploadOptionsBuilder setCannedAcl(String acl) {
        this.cannedAcl = acl;
        return this;
    }

    public UploadOptionsBuilder setOverallProgressListenerFactory
        (OverallProgressListenerFactory overallProgressListenerFactory) {
        this.overallProgressListenerFactory = overallProgressListenerFactory;
        return this;
    }

    public UploadOptionsBuilder setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public UploadOptionsBuilder setIgnoreAbortInjection(boolean ignore) {
        this.ignoreAbortInjection = ignore;
        return this;
    }

    private void validateOptions() {
        if (_cloudStoreClient == null) {
            throw new UsageException("CloudStoreClient has to be set");
        }
        else if (file == null) {
            throw new UsageException("File has to be set");
        }
        else if (bucket == null) {
            throw new UsageException("Bucket has to be set");
        }
        else if (objectKey == null) {
            throw new UsageException("Object key has to be set");
        }

        if (cannedAcl != null) {
            if (!_cloudStoreClient.getAclHandler().isCannedAclValid(cannedAcl)) {
                throw new UsageException("Invalid canned ACL '" + cannedAcl + "'");
            }
        }
        else {
            cannedAcl = _cloudStoreClient.getAclHandler().getDefaultAcl();
        }
    }

    @Override
    public UploadOptions createOptions() {
        validateOptions();

        return new UploadOptions(_cloudStoreClient, file, bucket, objectKey,
          chunkSize, encKey, cannedAcl, dryRun, ignoreAbortInjection,
          overallProgressListenerFactory);
    }
}