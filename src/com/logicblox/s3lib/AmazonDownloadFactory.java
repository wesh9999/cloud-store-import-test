package com.logicblox.s3lib;

import java.util.Map;
import java.util.concurrent.Callable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;

class AmazonDownloadFactory implements DownloadFactory
{
  private ListeningExecutorService executor;
  private AmazonS3 client;

  public AmazonDownloadFactory(AmazonS3 client, ListeningExecutorService executor)
  {
    this.client = client;
    this.executor = executor;
  }

  public ListenableFuture<Download> startDownload(String bucketName, String key)
  {
    return executor.submit(new GetObjectMetadataCallable(bucketName, key));
  }

  private class GetObjectMetadataCallable implements Callable<Download>
  {
    private String bucketName;
    private String key;

    public GetObjectMetadataCallable(String bucketName, String key)
    {
      this.bucketName = bucketName;
      this.key = key;
    }

    public Download call()
    {
      ObjectMetadata data = client.getObjectMetadata(bucketName, key);
      return new AmazonDownload(client, key, bucketName, data.getUserMetadata(), data.getContentLength(), executor);
    }
  }
}
