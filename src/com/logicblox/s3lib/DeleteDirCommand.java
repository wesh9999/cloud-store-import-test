package com.logicblox.s3lib;

import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;


public class DeleteDirCommand extends Command
{
  private ListeningExecutorService _httpExecutor;
  private ListeningScheduledExecutorService _executor;
  private DeleteOptions _options;
  private CloudStoreClient _client;

  public DeleteDirCommand(
    ListeningExecutorService httpExecutor,
    ListeningScheduledExecutorService internalExecutor,
    CloudStoreClient client,
    DeleteOptions opts)
  {
    if(httpExecutor == null)
      throw new IllegalArgumentException("non-null http executor is required");
    if(internalExecutor == null)
      throw new IllegalArgumentException("non-null internal executor is required");

    _httpExecutor = httpExecutor;
    _executor = internalExecutor;
    _client = client;
    _options = opts;
  }


  public ListenableFuture<List<S3File>> run()
    throws InterruptedException, ExecutionException
  {
    ListenableFuture<List<S3File>> listObjs = queryFiles();
    ListenableFuture<List<S3File>> result = Futures.transform(
      listObjs,
      new AsyncFunction<List<S3File>, List<S3File>>()
      {
        public ListenableFuture<List<S3File>> apply(List<S3File> potential)
        {
          List<S3File> matches = new ArrayList<S3File>();
          for(S3File f : potential)
          {
            if(!f.getKey().endsWith("/"))
              matches.add(f);
          }
          if(!_options.forceDelete() && matches.isEmpty())
          {
            throw new UsageException("No objects found that match '"
              + getUri(_options.getBucket(), _options.getObjectKey()) + "'");
          }

          List<ListenableFuture<S3File>> futures = prepareFutures(matches);

          if(_options.isDryRun())
            return Futures.immediateFuture(null);
          else
            return Futures.allAsList(futures);
        }
      });
    return result;
  }


  private List<ListenableFuture<S3File>> prepareFutures(List<S3File> toDelete)
  {
    List<ListenableFuture<S3File>> futures = new ArrayList<ListenableFuture<S3File>>();
    for(S3File src : toDelete)
    {
      if(_options.isDryRun())
      {
        System.out.println("<DRYRUN> deleting '"
          + getUri(src.getBucketName(), src.getKey()) + "'");
      }
      else
      {
        DeleteOptions opts = new DeleteOptionsBuilder()
          .setBucket(src.getBucketName())
          .setObjectKey(src.getKey())
          .createDeleteOptions();
        futures.add(_client.delete(opts));
      }
    }
    return futures;
  }


  private ListenableFuture<List<S3File>> queryFiles()
  {
    // find all files that need to be deleted
    ListOptions opts = new ListOptionsBuilder()
        .setBucket(_options.getBucket())
        .setObjectKey(_options.getObjectKey())
        .setRecursive(_options.isRecursive())
        .createListOptions();
    return _client.listObjects(opts);
  }
}