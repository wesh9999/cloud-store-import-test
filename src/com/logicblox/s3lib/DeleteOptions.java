package com.logicblox.s3lib;

import java.util.HashMap;
import java.util.Map;

public class DeleteOptions {
  
  private String _bucket;
  private String _objectKey;
  private boolean _recursive;
  private boolean _dryRun;
  private boolean _forceDelete;
  private boolean _ignoreAbortInjection;
  
  // for testing injecion of aborts during a delete
  private static AbortCounters _abortCounters = new AbortCounters();


  DeleteOptions(String bucket, String objectKey, boolean recursive, boolean dryRun,
    boolean forceDelete, boolean ignoreAbortInjection)
  {
    _bucket = bucket;
    _objectKey = objectKey;
    _recursive = recursive;
    _dryRun = dryRun;
    _forceDelete = forceDelete;
    _ignoreAbortInjection = ignoreAbortInjection;
  }
  
  // for testing injection of aborts during a copy
  void injectAbort(String id)
  {
    if(!_ignoreAbortInjection
         && (_abortCounters.decrementInjectionCounter(id) > 0))
    {
      throw new AbortInjection("forcing delete abort");
    }
  }

    static AbortCounters getAbortCounters()
    {
      return _abortCounters;
    }


  public String getBucket()
  {
    return _bucket;
  }
  
  public String getObjectKey()
  {
    return _objectKey;
  }
  
  public boolean isRecursive()
  {
    return _recursive;
  }
  
  public boolean isDryRun()
  {
    return _dryRun;
  }

  public boolean forceDelete()
  {
    return _forceDelete;
  }
}