package com.logicblox.s3lib;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

/**
 * Captures the full configuration independent of concrete uploads and
 * downloads.
 */
public class S3Client
{
  private ListeningExecutorService _s3Executor;
  private ListeningScheduledExecutorService _executor;
  private long _chunkSize;
  private AWSCredentialsProvider _credentials;
  private AmazonS3Client _client;
  private KeyProvider _keyProvider;
  private boolean _retryClientException = false;
  private int _retryCount = 50;

  /**
   * @param credentials   AWS Credentials
   * @param s3Executor    Executor for executing S3 API calls
   * @param executor      Executor for internally initiating uploads
   * @param chunkSize     Size of chunks
   * @param keyProvider   Provider of encryption keys
   */
  public S3Client(
    AWSCredentialsProvider credentials,
    ListeningExecutorService s3Executor,
    ListeningScheduledExecutorService executor,
    long chunkSize,
    KeyProvider keyProvider)
  {
    _executor = executor;
    _s3Executor = s3Executor;
    _chunkSize = chunkSize;
    _keyProvider = keyProvider;
    _credentials = credentials;
    if(_credentials != null)
      _client = new AmazonS3Client(_credentials);
    else
      _client = new AmazonS3Client();
  }

  public void setRetryCount(int retryCount)
  {
    _retryCount = retryCount;
  }

  public void setRetryClientException(boolean retry)
  {
    _retryClientException = retry;
  }

  private void configure(Command cmd)
  {
    cmd.setRetryClientException(_retryClientException);
    cmd.setRetryCount(_retryCount);
    cmd.setAmazonS3Client(_client);
  }

  /**
   * Upload file to S3 without encryption.
   *
   * @param file    File to upload
   * @param s3url   S3 object URL (using same syntax as s3cmd)
   */
  public ListenableFuture<String> upload(File file, URI s3url)
  throws FileNotFoundException, IOException
  {
    return upload(file, s3url, null);
  }

  /**
   * Upload file to S3 without encryption.
   *
   * @param file    File to upload
   * @param bucket  Bucket to upload to
   * @param object  Path in bucket to upload to
   */
  public ListenableFuture<String> upload(File file, String bucket, String object)
  throws FileNotFoundException, IOException
  {
    return upload(file, bucket, object, null);
  }

  /**
   * Upload file to S3.
   *
   * @param file    File to upload
   * @param bucket  Bucket to upload to
   * @param object  Path in bucket to upload to
   * @param key     Name of encryption key to use
   */
  public ListenableFuture<String> upload(File file, String bucket, String object, String key)
  throws FileNotFoundException, IOException
  {
    UploadCommand cmd =
      new UploadCommand(_s3Executor, _executor, file, _chunkSize, key, _keyProvider);
    configure(cmd);
    return cmd.run(bucket, object); 
  }

  /**
   * Upload file to S3.
   *
   * @param file    File to upload
   * @param s3url   S3 object URL to upload to
   * @param key     Name of encryption key to use
   * @throws IllegalArgumentException If the s3url is not a valid S3 URL.
   */
  public ListenableFuture<String> upload(File file, URI s3url, String key)
  throws FileNotFoundException, IOException
  {
    if(!"s3".equals(s3url.getScheme()))
      throw new IllegalArgumentException("S3 object URL needs to have 's3' as scheme");

    String bucket = Utils.getBucket(s3url);
    String object = Utils.getObjectKey(s3url);
    return upload(file, bucket, object, key);
  }

      // TODO would be useful to get a command hash back
      // (e.g. SHA-512) so that we can use that in authentication.

  /**
   * Download file from S3
   *
   * @param file    File to download
   * @param bucket  Bucket to download from
   * @param object  Path in bucket to download
   */
  public ListenableFuture<?> download(File file, String bucket, String object)
  throws IOException
  {
    DownloadCommand cmd = new DownloadCommand(_s3Executor, _executor, file, _keyProvider);
    configure(cmd);
    return cmd.run(bucket, object); 
  }

  /**
   * Download file from S3
   *
   * @param file    File to download
   * @param s3url   S3 object URL to download from
   * @throws IllegalArgumentException If the s3url is not a valid S3 URL.
   */
  public ListenableFuture<?> download(File file, URI s3url)
  throws IOException
  {
    if(!"s3".equals(s3url.getScheme()))
      throw new IllegalArgumentException("S3 object URL needs to have 's3' as scheme");

    String bucket = Utils.getBucket(s3url);
    String object = Utils.getObjectKey(s3url);
    return download(file, bucket, object);
  }

  public void shutdown()
  {
    try
    {
      _client.shutdown();
    }
    catch(Exception exc)
    {
      exc.printStackTrace();
    }

    try
    {
      _s3Executor.shutdown();
    }
    catch(Exception exc)
    {
      exc.printStackTrace();
    }
    
    try
    {
      _executor.shutdown();
    }
    catch(Exception exc)
    {
      exc.printStackTrace();
    }
  }
}