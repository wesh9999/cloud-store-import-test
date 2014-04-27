package com.logicblox.s3lib;


import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.*;
import com.logicblox.s3lib.Command;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class DownloadDirectoryCommand extends Command
{
  private ListeningExecutorService _httpExecutor;
  private ListeningScheduledExecutorService _executor;
  private S3Client _client;

  public DownloadDirectoryCommand(
          ListeningExecutorService httpExecutor,
          ListeningScheduledExecutorService internalExecutor,
          S3Client client)
  {
    _httpExecutor = httpExecutor;
    _executor = internalExecutor;
    _client = client;
  }

  public ListenableFuture<?> run(final File file, final String bucket, final String key, final boolean recursive, final boolean overwrite) throws ExecutionException, InterruptedException, IOException {
    List<S3ObjectSummary> lst = _client.listObjects(bucket, key, recursive).get();

    if (lst.size() > 1) {
      if( !file.exists())
        if (! file.mkdirs())
          throw new UsageException("Could not create directory '"+file+"'");
    }

    List<ListenableFuture<?>> files = new ArrayList<ListenableFuture<?>>();

    for (S3ObjectSummary obj : lst) {
      String relFile = obj.getKey().substring(key.length());
      File outputFile = new File(file.getAbsoluteFile(), relFile);
      File outputPath = new File(outputFile.getParent());

      if(! outputPath.exists())
        if( ! outputPath.mkdirs())
          throw new UsageException("Could not create directory '"+file+"'");

      if (! obj.getKey().endsWith("/")) {
        if(outputFile.exists())
        {
          if(overwrite)
          {
            if(!outputFile.delete())
              throw new UsageException("Could not overwrite existing file '" + file + "'");
          }
          else
            throw new UsageException("File '" + file + "' already exists. Please delete or use --overwrite");
        }

        ListenableFuture<?> result = _client.download(outputFile, bucket, obj.getKey());
        files.add(result);
      }
    }

    return Futures.transform(Futures.allAsList(files), Functions.constant(null));
  }

}