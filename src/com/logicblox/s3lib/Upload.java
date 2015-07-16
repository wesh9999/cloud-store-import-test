package com.logicblox.s3lib;

import java.io.InputStream;
import java.util.Date;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;

interface Upload
{
  ListenableFuture<Void> uploadPart(int partNumber, InputStream stream, long
      partSize, Optional<OverallProgressListener> opl);

  ListenableFuture<String> completeUpload();

  ListenableFuture<Void> abort();

  String getBucket();

  String getKey();

  String getId();

  Date getInitiationDate();
}
