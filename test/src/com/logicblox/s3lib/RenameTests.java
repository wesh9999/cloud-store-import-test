package com.logicblox.s3lib;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import junit.framework.Assert;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RenameTests
  implements RetryListener
{
  private static CloudStoreClient _client = null;
  private static String _testBucket = null;
  private int _retryCount = 0;
  

  // RetryListener
  @Override
  public synchronized void retryTriggered(RetryEvent e)
  {
    ++_retryCount;
  }


  @BeforeClass
  public static void setUp()
    throws Throwable
  {
    TestUtils.setUp();
    _testBucket = TestUtils.getTestBucket();
    _client = TestUtils.getClient();
  }


  @AfterClass
  public static void tearDown()
    throws Throwable
  {
    TestUtils.tearDown();
    _testBucket = null;
    _client = null;
  }


  @Test
  public void testDryRunFile()
    throws Throwable
  {
    // create test file and upload it
    String rootPrefix = TestUtils.addPrefix("rename-dryrun");
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, dest);
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // dryrun the rename and make sure dest stays the same
    URI src = dest;
    dest = TestUtils.getUri(_testBucket, toUpload.getName() + "-RENAME", rootPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .setDryRun(true)
      .createRenameOptions();
    f = _client.rename(opts).get();
    Assert.assertNull(f);
    List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
    Assert.assertEquals(originalCount + 1, objs.size());
    Assert.assertTrue(TestUtils.findObject(objs, Utils.getObjectKey(src)));
    Assert.assertFalse(TestUtils.findObject(objs, Utils.getObjectKey(dest)));
  }

  
  @Test
  public void testDryRunDir()
    throws Throwable
  {
// directory copy/upload tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create simple directory structure with a few files and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    String rootPrefix = TestUtils.addPrefix("rename-dryrun-dir/");
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, dest);
    Assert.assertEquals(2, uploaded.size());
    Assert.assertEquals(
      originalCount + 2, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // dryrun the rename and make sure the dest doesn't change
    URI src = dest;
    dest = TestUtils.getUri(_testBucket, top.getName() + "-RENAME", rootPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .setRecursive(false)
      .setDryRun(true)
      .createRenameOptions();
    List<S3File> files = _client.renameDirectory(opts).get();
    Assert.assertNull(files);
    List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
    Assert.assertEquals(originalCount + 2, objs.size());

    String topN = rootPrefix + top.getName() + "/";
    Assert.assertTrue(TestUtils.findObject(objs, topN + a.getName()));
    Assert.assertTrue(TestUtils.findObject(objs, topN + b.getName()));

    String renameN = rootPrefix + top.getName() + "-RENAME" + "/";
    Assert.assertFalse(TestUtils.findObject(objs, renameN + a.getName()));
    Assert.assertFalse(TestUtils.findObject(objs, renameN + b.getName()));

    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testRenameDirAbortOneDuringCopy()
    throws Throwable
  {
    // directory copy/upload/rename tests intermittently fail when using minio.
    // trying to minimize false failure reports by repeating and only failing
    // the test if it consistently reports an error.
    int retryCount = TestUtils.RETRY_COUNT;
    int count = 0;
while(count < retryCount)
{
    boolean oldGlobalFlag = false;
    try
    {
      // create simple directory structure and upload
      File top = TestUtils.createTmpDir(true);
      File a = TestUtils.createTextFile(top, 100);
      File b = TestUtils.createTextFile(top, 100);
      File c = TestUtils.createTextFile(top, 100);

      String rootPrefix = TestUtils.addPrefix("rename-dir-abort-one-on-copy-" + count + "/");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
      List<S3File> uploaded = TestUtils.uploadDir(top, dest);
      Assert.assertEquals(3, uploaded.size());
      int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      Assert.assertEquals(uploaded.size(), uploadCount);

      // rename the directory
      URI src = dest;
      String destPrefix = TestUtils.addPrefix("rename-dir-abort-one-on-copy-dest-"
        + count + "/subdir/");
      int newCount = TestUtils.listObjects(_testBucket, destPrefix).size();
      dest = TestUtils.getUri(_testBucket, "subdir2", destPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest) + "/")
        .setRecursive(true)
        .createRenameOptions();
      oldGlobalFlag = CopyOptions.getAbortCounters().useGlobalCounter(true);
      CopyOptions.getAbortCounters().setInjectionCounter(1);
         // abort first rename during copy phase
      try
      {
        _client.renameDirectory(opts).get();
      }
      catch(ExecutionException ex)
      {
        // expected for one of the rename jobs
        Assert.assertTrue(ex.getMessage().contains("forcing copy abort"));
      }
      
      // verify that nothing moved
      List<S3File> destObjs = TestUtils.listObjects(_testBucket, destPrefix);
      String topDestN = destPrefix + "subdir2/";
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + a.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + b.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + c.getName()));

      List<S3File> srcObjs = TestUtils.listObjects(_testBucket, rootPrefix);
      String topN = rootPrefix + top.getName() + "/";
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + a.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + b.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + c.getName()));

      return;
    }
    catch(Throwable t)
    {
      ++count;
      if(count >= retryCount)
        throw t;
    }
    finally
    {
      // reset abort injection so other tests aren't affected
      CopyOptions.getAbortCounters().useGlobalCounter(oldGlobalFlag);
      CopyOptions.getAbortCounters().setInjectionCounter(0);
      CopyOptions.getAbortCounters().clearInjectionCounters();
    }
}
  }


// FIXME - need to find a way to abort a random operation.  always aborting the
//         first which can hide any cleanup problems when trying to undo a 
//         partial rename
// FIXME - disabling this test for now until we can find a robust way to correctly
//         recover from these failures
//  @Test
  public void testRenameDirAbortOneDuringDelete()
    throws Throwable
  {
    // directory copy/upload/rename tests intermittently fail when using minio.
    // trying to minimize false failure reports by repeating and only failing
    // the test if it consistently reports an error.
    int retryCount = TestUtils.RETRY_COUNT;
    int count = 0;
while(count < retryCount)
{
    try
    {
      // create simple directory structure and upload
      File top = TestUtils.createTmpDir(true);
      File a = TestUtils.createTextFile(top, 100);
      File b = TestUtils.createTextFile(top, 100);
      File c = TestUtils.createTextFile(top, 100);

      String rootPrefix = TestUtils.addPrefix("rename-dir-abort-one-on-delete-" + count + "/");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
      List<S3File> uploaded = TestUtils.uploadDir(top, dest);
      Assert.assertEquals(3, uploaded.size());
      int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      Assert.assertEquals(uploaded.size(), uploadCount);

      // rename the directory
      URI src = dest;
      String destPrefix = TestUtils.addPrefix("rename-dir-abort-one-on-delete-dest-"
        + count + "/subdir/");
      int newCount = TestUtils.listObjects(_testBucket, destPrefix).size();
      dest = TestUtils.getUri(_testBucket, "subdir2", destPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest) + "/" )
        .setRecursive(true)
        .createRenameOptions();
      boolean oldGlobalFlag = false;
      try
      {
        oldGlobalFlag = DeleteOptions.getAbortCounters().useGlobalCounter(true);
        DeleteOptions.getAbortCounters().setInjectionCounter(1);
           // abort first rename during delete phase
        _client.renameDirectory(opts).get();
      }
      catch(ExecutionException ex)
      {
        // expected for one of the rename jobs
        Assert.assertTrue(ex.getMessage().contains("forcing delete abort"));
      }
      finally
      {
        // reset abort injection so other tests aren't affected
        DeleteOptions.getAbortCounters().useGlobalCounter(oldGlobalFlag);
        DeleteOptions.getAbortCounters().setInjectionCounter(0);
        DeleteOptions.getAbortCounters().clearInjectionCounters();
      }
      
      // verify that nothing moved
      List<S3File> destObjs = TestUtils.listObjects(_testBucket, destPrefix);
      List<S3File> srcObjs = TestUtils.listObjects(_testBucket, rootPrefix);

      String topDestN = destPrefix + "subdir2/";
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + a.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + b.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + c.getName()));

      String topN = rootPrefix + top.getName() + "/";
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + a.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + b.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + c.getName()));

      return;
    }
    catch(Throwable t)
    {
      ++count;
      if(count >= retryCount)
        throw t;
    }
}
  }


// FIXME - disabling this test for now until we can find a robust way to correctly
//         recover from these failures
//  @Test
  public void testRenameDirAllAbortDuringDelete()
    throws Throwable
  {
    // NOTE: this test dumps a stack trace that can be ignored
    
    // directory copy/upload/rename tests intermittently fail when using minio.
    // trying to minimize false failure reports by repeating and only failing
    // the test if it consistently reports an error.
    int retryCount = TestUtils.RETRY_COUNT;
    int count = 0;
while(count < retryCount)
{
    try
    {
      // create simple directory structure and upload
      File top = TestUtils.createTmpDir(true);
      File a = TestUtils.createTextFile(top, 100);
      File b = TestUtils.createTextFile(top, 100);

      String rootPrefix = TestUtils.addPrefix("rename-dir-all-abort-on-delete-" + count + "/");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
      List<S3File> uploaded = TestUtils.uploadDir(top, dest);
      Assert.assertEquals(2, uploaded.size());
      int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      Assert.assertEquals(uploaded.size(), uploadCount);

      // rename the directory
      URI src = dest;
      String destPrefix = TestUtils.addPrefix("rename-dir-all-abort-on-delete-dest-"
        + count + "/subdir/");
      int newCount = TestUtils.listObjects(_testBucket, destPrefix).size();
      dest = TestUtils.getUri(_testBucket, "subdir2", destPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest) + "/")
        .setRecursive(true)
        .createRenameOptions();
      DeleteOptions.getAbortCounters().setInjectionCounter(1);
        // should be one more than retry count.  retries disabled by default
      String msg = null;
      try
      {
        _client.renameDirectory(opts).get();
        msg = "expected exception (forcing abort on delete)";
      }
      catch(ExecutionException ex)
      {
        // expected
        Assert.assertTrue(TestUtils.findCause(ex, AbortInjection.class));
        Assert.assertTrue(ex.getMessage().contains("forcing delete abort"));
      }
      Assert.assertNull(msg);
      
      // verify that nothing moved
      List<S3File> destObjs = TestUtils.listObjects(_testBucket, destPrefix);
      String topDestN = destPrefix + "subdir2/";
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + a.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + b.getName()));

      List<S3File> srcObjs = TestUtils.listObjects(_testBucket, rootPrefix);
      String topN = rootPrefix + top.getName();
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + "/" + a.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + "/" + b.getName()));

      return;
    }
    catch(Throwable t)
    {
      ++count;
      if(count >= retryCount)
        throw t;
    }
    finally
    {
      // reset abort injection so other tests aren't affected
      DeleteOptions.getAbortCounters().setInjectionCounter(0);
      DeleteOptions.getAbortCounters().clearInjectionCounters();
    }
}
  }


  @Test
  public void testRenameDirAllAbortDuringCopy()
    throws Throwable
  {
    // NOTE: this test dumps a stack trace that can be ignored
    
    // directory copy/upload/rename tests intermittently fail when using minio.
    // trying to minimize false failure reports by repeating and only failing
    // the test if it consistently reports an error.
    int retryCount = TestUtils.RETRY_COUNT;
    int count = 0;
while(count < retryCount)
{
    try
    {
      // create simple directory structure and upload
      File top = TestUtils.createTmpDir(true);
      File a = TestUtils.createTextFile(top, 100);
      File b = TestUtils.createTextFile(top, 100);

      String rootPrefix = TestUtils.addPrefix("rename-dir-all-abort-on-copy-" + count + "/");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
      List<S3File> uploaded = TestUtils.uploadDir(top, dest);
      Assert.assertEquals(2, uploaded.size());
      int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      Assert.assertEquals(uploaded.size(), uploadCount);

      // rename the directory
      URI src = dest;
      String destPrefix = TestUtils.addPrefix("rename-dir-all-abort-on-copy-dest-"
        + count + "/subdir/");
      int newCount = TestUtils.listObjects(_testBucket, destPrefix).size();
      dest = TestUtils.getUri(_testBucket, "subdir2", destPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest) + "/")
        .setRecursive(true)
        .createRenameOptions();
      CopyOptions.getAbortCounters().setInjectionCounter(1);
        // should be one more than retry count.  retries disabled by default
      String msg = null;
      try
      {
        _client.renameDirectory(opts).get();
        msg = "expected exception (forcing abort on copy)";
      }
      catch(ExecutionException ex)
      {
        // expected
        Assert.assertTrue(TestUtils.findCause(ex, AbortInjection.class));
        Assert.assertTrue(ex.getMessage().contains("forcing copy abort"));
      }
      Assert.assertNull(msg);
      
      // verify that nothing moved
      List<S3File> destObjs = TestUtils.listObjects(_testBucket, destPrefix);
      String topDestN = destPrefix + "subdir2/";
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + a.getName()));
      Assert.assertFalse(TestUtils.findObject(destObjs, topDestN + b.getName()));

      List<S3File> srcObjs = TestUtils.listObjects(_testBucket, rootPrefix);
      String topN = rootPrefix + top.getName();
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + "/" + a.getName()));
      Assert.assertTrue(TestUtils.findObject(srcObjs, topN + "/" + b.getName()));

      return;
    }
    catch(Throwable t)
    {
      ++count;
      if(count >= retryCount)
        throw t;
    }
    finally
    {
      // reset abort injection so other tests aren't affected
      CopyOptions.getAbortCounters().setInjectionCounter(0);
      CopyOptions.getAbortCounters().clearInjectionCounters();
    }
}
  }

  
  @Test
  public void testRetryOnCopy()
    throws Throwable
  {
    try
    {
      // create a small file and upload it
      String rootPrefix = TestUtils.addPrefix("rename-retry-during-copy-");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      File toUpload = TestUtils.createTextFile(100);
      URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
      S3File f = TestUtils.uploadFile(toUpload, dest);
      Assert.assertNotNull(f);
      Assert.assertEquals(
        originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

      // set retry options and aborts during copy phase of rename
      ThrowableRetriableTask.addRetryListener(this);
      clearRetryCount();
      int retryCount = 10;
      int abortCount = 3;
      _client.setRetryCount(retryCount);
      CopyOptions.getAbortCounters().setInjectionCounter(abortCount);

      // rename the file
      URI src = dest;
      dest = TestUtils.getUri(_testBucket, toUpload.getName() + "-RENAMED", rootPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest))
        .createRenameOptions();
      f = _client.rename(opts).get();

      // verify that the rename succeeded and we triggered the right number of retries
      Assert.assertEquals(abortCount, getRetryCount());
      Assert.assertNotNull(f);
      Assert.assertEquals(Utils.getObjectKey(dest), f.getKey());
      List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
      Assert.assertTrue(TestUtils.findObject(objs, Utils.getObjectKey(dest)));
      Assert.assertFalse(TestUtils.findObject(objs, Utils.getObjectKey(src)));
    }
    finally
    {
      // reset retry and abort injection state so we don't affect other tests
      TestUtils.resetRetryCount();
      CopyOptions.getAbortCounters().setInjectionCounter(0);
      CopyOptions.getAbortCounters().clearInjectionCounters();
    }
  }


  @Test
  public void testRetryOnDelete()
    throws Throwable
  {
    try
    {
      // create a small file and upload it
      String rootPrefix = TestUtils.addPrefix("rename-retry-during-delete-");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      File toUpload = TestUtils.createTextFile(100);
      URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
      S3File f = TestUtils.uploadFile(toUpload, dest);
      Assert.assertNotNull(f);
      Assert.assertEquals(
        originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

      // set retry options and aborts during copy phase of rename
      ThrowableRetriableTask.addRetryListener(this);
      clearRetryCount();
      int retryCount = 10;
      int abortCount = 3;
      _client.setRetryCount(retryCount);
      DeleteOptions.getAbortCounters().setInjectionCounter(abortCount);

      // rename the file
      URI src = dest;
      dest = TestUtils.getUri(_testBucket, toUpload.getName() + "-RENAMED", rootPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest))
        .createRenameOptions();
      f = _client.rename(opts).get();

      // verify that the rename succeeded and we triggered the right number of retries
      Assert.assertEquals(abortCount, getRetryCount());
      Assert.assertNotNull(f);
      Assert.assertEquals(Utils.getObjectKey(dest), f.getKey());
      List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
      Assert.assertTrue(TestUtils.findObject(objs, Utils.getObjectKey(dest)));
      Assert.assertFalse(TestUtils.findObject(objs, Utils.getObjectKey(src)));
    }
    finally
    {
      // reset retry and abort injection state so we don't affect other tests
      TestUtils.resetRetryCount();
      DeleteOptions.getAbortCounters().setInjectionCounter(0);
      DeleteOptions.getAbortCounters().clearInjectionCounters();
    }
  }


  @Test
  public void testAbortDuringCopy()
    throws Throwable
  {
    try
    {
      // create a small file and upload it
      String rootPrefix = TestUtils.addPrefix("rename-abort-during-copy-");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      File toUpload = TestUtils.createTextFile(100);
      URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
      S3File f = TestUtils.uploadFile(toUpload, dest);
      Assert.assertNotNull(f);
      Assert.assertEquals(
        originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

      // rename the file
      URI src = dest;
      dest = TestUtils.getUri(_testBucket, toUpload.getName() + "-RENAMED", rootPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest))
        .createRenameOptions();
      CopyOptions.getAbortCounters().setInjectionCounter(1);
        // should be one more than retry count.  retries disabled by default
      String msg = null;
      try
      {
        _client.rename(opts).get();
        msg = "expected exception (forcing abort on copy)";
      }
      catch(ExecutionException ex)
      {
        // expected
        Assert.assertTrue(TestUtils.findCause(ex, AbortInjection.class));
        Assert.assertTrue(ex.getMessage().contains("forcing copy abort"));
      }
      Assert.assertNull(msg);

      // file should not be renamed since we aborted
      List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
      Assert.assertFalse(TestUtils.findObject(objs, Utils.getObjectKey(dest)));
      Assert.assertTrue(TestUtils.findObject(objs, Utils.getObjectKey(src)));
    }
    finally
    {
      // reset abort injection so other tests aren't affected
      CopyOptions.getAbortCounters().setInjectionCounter(0);
      CopyOptions.getAbortCounters().clearInjectionCounters();
    }
  }

  
  @Test
  public void testAbortDuringDelete()
    throws Throwable
  {
    try
    {
      // create a small file and upload it
      String rootPrefix = TestUtils.addPrefix("rename-abort-during-delete-");
      int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
      File toUpload = TestUtils.createTextFile(100);
      URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
      S3File f = TestUtils.uploadFile(toUpload, dest);
      Assert.assertNotNull(f);
      Assert.assertEquals(
        originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

      // rename the file
      URI src = dest;
      dest = TestUtils.getUri(_testBucket, toUpload.getName() + "-RENAMED", rootPrefix);
      RenameOptions opts = new RenameOptionsBuilder()
        .setSourceBucket(Utils.getBucket(src))
        .setSourceKey(Utils.getObjectKey(src))
        .setDestinationBucket(Utils.getBucket(dest))
        .setDestinationKey(Utils.getObjectKey(dest))
        .createRenameOptions();
      DeleteOptions.getAbortCounters().setInjectionCounter(1);
         // should be one more than retry count.  retries disabled by default
      String msg = null;
      try
      {
        _client.rename(opts).get();
        msg = "expected exception (forcing abort on copy)";
      }
      catch(ExecutionException ex)
      {
        // expected
        Assert.assertTrue(TestUtils.findCause(ex, AbortInjection.class));
        Assert.assertTrue(ex.getMessage().contains("forcing delete abort"));
      }
      Assert.assertNull(msg);

      // file should not be renamed since we aborted
      List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
      Assert.assertFalse(TestUtils.findObject(objs, Utils.getObjectKey(dest)));
      Assert.assertTrue(TestUtils.findObject(objs, Utils.getObjectKey(src)));
    }
    finally
    {
      // reset abort injection so other tests aren't affected
      DeleteOptions.getAbortCounters().setInjectionCounter(0);
      DeleteOptions.getAbortCounters().clearInjectionCounters();
    }
  }


  @Test
  public void testSimpleObject()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create a small file and upload it
    String rootPrefix = TestUtils.addPrefix("rename-simple-" + count);
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, dest);
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // rename the file
    URI src = dest;
    String newPrefix = TestUtils.addPrefix("rename-simple-dest-" + count + "/c/d/");
    int newCount = TestUtils.listObjects(_testBucket, newPrefix).size();
    dest = TestUtils.getUri(_testBucket, "new-file.txt", newPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    f = _client.rename(opts).get();
    Assert.assertNotNull(f);
    Assert.assertEquals(Utils.getObjectKey(dest), f.getKey());
    Assert.assertEquals(Utils.getBucket(dest), f.getBucketName());

    // verify that it moved
    Assert.assertEquals(
      originalCount, TestUtils.listObjects(_testBucket, rootPrefix).size());
    Assert.assertNull(
      _client.exists(Utils.getBucket(src), Utils.getObjectKey(src)).get());
    Assert.assertNotNull(
      _client.exists(Utils.getBucket(dest), Utils.getObjectKey(dest)).get());
    List<S3File> objs = TestUtils.listObjects(_testBucket, newPrefix);
    Assert.assertEquals(newCount + 1, objs.size());
    Assert.assertTrue(TestUtils.findObject(objs, newPrefix + "new-file.txt"));
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testDestExists()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create two small files and upload
    String rootPrefix = TestUtils.addPrefix("rename-dest-exists-" + count);
    File file1 = TestUtils.createTextFile(100);
    URI dest1 = TestUtils.getUri(_testBucket, file1, rootPrefix);
    S3File f = TestUtils.uploadFile(file1, dest1);
    Assert.assertNotNull(f);

    File file2 = TestUtils.createTextFile(100);
    URI dest2 = TestUtils.getUri(_testBucket, file2, rootPrefix);
    f = TestUtils.uploadFile(file2, dest2);
    Assert.assertNotNull(f);

    // rename file1 to file2
    URI src = dest1;
    URI dest = dest2;
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    String msg = null;
    try
    {
      _client.rename(opts).get();
      msg = "expected exception (dest exists)";
    }
    catch(Exception ex)
    {
      // expected
      checkUsageException(ex, "Cannot overwrite existing destination");
    }
    Assert.assertNull(msg);
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testSameSourceAndDest()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create a small files and upload
    String rootPrefix = TestUtils.addPrefix("rename-same-src-dest-" + count);
    File file = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, file, rootPrefix);
    S3File f = TestUtils.uploadFile(file, dest);
    Assert.assertNotNull(f);

    // rename 
    URI src = dest;
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    String msg = null;
    try
    {
      _client.rename(opts).get();
      msg = "expected exception (dest exists)";
    }
    catch(Exception ex)
    {
      // expected
      checkUsageException(ex, "Cannot overwrite existing destination");
    }
    Assert.assertNull(msg);
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testMissingSource()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    String rootPrefix = TestUtils.addPrefix("rename-missing-source-" + count);
    URI src = TestUtils.getUri(
      _testBucket, "rename-missing-file" + System.currentTimeMillis(), rootPrefix);
    URI dest = TestUtils.getUri(_testBucket, "dummy.txt", rootPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    String msg = null;
    try
    {
      _client.rename(opts).get();
      msg = "expected exception (source missing)";
    }
    catch(Exception ex)
    {
      // expected
      checkUsageException(ex, "does not exist");
    }
    Assert.assertNull(msg);
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testMoveObjectAcrossBuckets()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // skip this if we're using a pre-exising test bucket, assuming we're
    // running against a server that we don't want to (or can't) create
    // buckets in...
    if(null != TestUtils.getPrefix())
       return;

    // create a small file and upload it
    String rootPrefix = TestUtils.addPrefix("rename-move-obj-across-buckets-" + count);
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, dest);
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // rename the file
    String bucket2 = TestUtils.createTestBucket();
    URI src = dest;
    String newPrefix = TestUtils.addPrefix("rename-move-obj-across-buckets-dest-" + count + "/c/d/");
    int newCount = TestUtils.listObjects(bucket2, newPrefix).size();
    dest = TestUtils.getUri(bucket2, "new-file.txt", newPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    f = _client.rename(opts).get();
    Assert.assertEquals(Utils.getObjectKey(dest), f.getKey());
    Assert.assertEquals(Utils.getBucket(dest), f.getBucketName());

    // verify that it moved
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount, TestUtils.listObjects(_testBucket, rootPrefix).size());
    Assert.assertNull(
      _client.exists(Utils.getBucket(src), Utils.getObjectKey(src)).get());
    Assert.assertNotNull(
      _client.exists(Utils.getBucket(dest), Utils.getObjectKey(dest)).get());
    List<S3File> objs = TestUtils.listObjects(bucket2, newPrefix);
    Assert.assertEquals(newCount + 1, objs.size());
    Assert.assertTrue(TestUtils.findObject(objs, newPrefix + "new-file.txt"));
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testNonRecursiveDirectory()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create simple directory structure and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    File sub = TestUtils.createTmpDir(top);
    File c = TestUtils.createTextFile(sub, 100);
    File d = TestUtils.createTextFile(sub, 100);
    File sub2 = TestUtils.createTmpDir(sub);
    File e = TestUtils.createTextFile(sub2, 100);

    String rootPrefix = TestUtils.addPrefix("rename-directory-" + count + "/");
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, dest);
    Assert.assertEquals(5, uploaded.size());
    int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    Assert.assertEquals(uploaded.size(), uploadCount);

    // rename the directory
    URI src = dest;
    String newPrefix = TestUtils.addPrefix("rename-dir-dest-" + count + "/subdir/");
    int newCount = TestUtils.listObjects(_testBucket, newPrefix).size();
    dest = TestUtils.getUri(_testBucket, "subdir2", newPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .setRecursive(false)
      .createRenameOptions();
    List<S3File> renamedFiles = _client.renameDirectory(opts).get();

    // verify that top level objects moved (a and b), but others stayed
    Assert.assertEquals(2, renamedFiles.size());
    for(S3File f : renamedFiles)
      Assert.assertEquals(Utils.getBucket(dest), f.getBucketName());
    List<S3File> newObjs = TestUtils.listObjects(_testBucket, newPrefix);
    Assert.assertEquals(newCount + renamedFiles.size(), newObjs.size());
    Assert.assertEquals(
      uploadCount - renamedFiles.size(),
      TestUtils.listObjects(_testBucket, rootPrefix).size());

    // verify that the structure was replicated correctly
    String topN = newPrefix + "subdir2/";
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + a.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + b.getName()));
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testRecursiveDirectory()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create simple directory structure and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    File sub = TestUtils.createTmpDir(top);
    File c = TestUtils.createTextFile(sub, 100);
    File d = TestUtils.createTextFile(sub, 100);
    File sub2 = TestUtils.createTmpDir(sub);
    File e = TestUtils.createTextFile(sub2, 100);

    String rootPrefix = TestUtils.addPrefix("rename-dir-recursive-" + count + "/");
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, dest);
    Assert.assertEquals(5, uploaded.size());
    int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    Assert.assertEquals(uploaded.size(), uploadCount);

    // rename the directory
    URI src = dest;
    String newPrefix = TestUtils.addPrefix("rename-dir-recursive-dest-" + count + "/subdir/");
    int newCount = TestUtils.listObjects(_testBucket, newPrefix).size();
    dest = TestUtils.getUri(_testBucket, "subdir2", newPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .setRecursive(true)
      .createRenameOptions();
    List<S3File> renamedFiles = _client.renameDirectory(opts).get();

    // verify that everything moved
    Assert.assertEquals(uploadCount, renamedFiles.size());
    for(S3File f : renamedFiles)
      Assert.assertEquals(Utils.getBucket(dest), f.getBucketName());
    List<S3File> newObjs = TestUtils.listObjects(_testBucket, newPrefix);
    Assert.assertEquals(newCount + renamedFiles.size(), newObjs.size());
    Assert.assertEquals(
      uploadCount - renamedFiles.size(),
      TestUtils.listObjects(_testBucket, rootPrefix).size());

    // verify that the structure was replicated correctly
    String topN = newPrefix + "subdir2/";
    String subN = topN + sub.getName() + "/";
    String sub2N = subN + sub2.getName() + "/";
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + a.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + b.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, subN + c.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, subN + d.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, sub2N + e.getName()));
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }
  

  @Test
  public void testMoveDirectoryAcrossBuckets()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // skip this if we're using a pre-exising test bucket, assuming we're
    // running against a server that we don't want to (or can't) create
    // buckets in...
    if(null != TestUtils.getPrefix())
       return;

    // create simple directory structure and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    File sub = TestUtils.createTmpDir(top);
    File c = TestUtils.createTextFile(sub, 100);
    File d = TestUtils.createTextFile(sub, 100);
    File sub2 = TestUtils.createTmpDir(sub);
    File e = TestUtils.createTextFile(sub2, 100);

    String rootPrefix = TestUtils.addPrefix("rename-dir-across-buckets-" + count + "/");
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    URI dest = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, dest);
    Assert.assertEquals(5, uploaded.size());
    int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    Assert.assertEquals(uploaded.size(), uploadCount);

    // rename the directory
    String bucket2 = TestUtils.createTestBucket();
    URI src = dest;
    String newPrefix = TestUtils.addPrefix("rename-dir-across-buckets-dest-" + count + "/subdir/");
    int newCount = TestUtils.listObjects(bucket2, newPrefix).size();
    dest = TestUtils.getUri(bucket2, "subdir2", newPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .setRecursive(true)
      .createRenameOptions();
    List<S3File> renamedFiles = _client.renameDirectory(opts).get();

    // verify that everything moved
    Assert.assertEquals(uploadCount, renamedFiles.size());
    for(S3File f : renamedFiles)
      Assert.assertEquals(Utils.getBucket(dest), f.getBucketName());
    List<S3File> newObjs = TestUtils.listObjects(bucket2, newPrefix);
    Assert.assertEquals(newCount + renamedFiles.size(), newObjs.size());
    Assert.assertEquals(
      uploadCount - renamedFiles.size(),
      TestUtils.listObjects(_testBucket, rootPrefix).size());

    // verify that the structure was replicated correctly
    String topN = newPrefix + "subdir2/";
    String subN = topN + sub.getName() + "/";
    String sub2N = subN + sub2.getName() + "/";
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + a.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + b.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, subN + c.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, subN + d.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, sub2N + e.getName()));
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }
  

  @Test
  public void testMissingSourceDir()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    String rootPrefix = TestUtils.addPrefix("rename-missing-src-dir-" + count);
    URI src = TestUtils.getUri(
      _testBucket, "rename-missing-dir" + System.currentTimeMillis(), rootPrefix);
    URI dest = TestUtils.getUri(_testBucket, "subdir", rootPrefix);
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .createRenameOptions();
    String msg = null;
    try
    {
      _client.renameDirectory(opts).get();
      msg = "expected exception (source missing)";
    }
    catch(Exception ex)
    {
      // expected
      checkUsageException(ex, "No objects found");
    }
    Assert.assertNull(msg);
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testDirOverwriteFile()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create a small file and upload it
    String rootPrefix = TestUtils.addPrefix("rename-dir-overwrite-file-" + count);
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    File toUpload = TestUtils.createTextFile(100);
    URI destFile = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, destFile);
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // create simple directory structure and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    URI destDir = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, destDir);
    Assert.assertEquals(2, uploaded.size());
    int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    Assert.assertEquals(uploaded.size() + 1, uploadCount);

    // attempt the rename -- should fail
    URI src = destDir;
    URI dest = destFile;
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest) + "/")
      .createRenameOptions();
    String msg = null;
    try
    {
      _client.renameDirectory(opts).get();
      msg = "expected exception (source missing)";
    }
    catch(Exception ex)
    {
      // expected
      checkUsageException(ex, "Cannot overwrite existing destination");
    }
    Assert.assertNull(msg);
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  @Test
  public void testMoveFileIntoExistingDir()
    throws Throwable
  {
// directory copy/upload/rename tests intermittently fail when using minio.  trying to minimize false failure reports by repeating and only failing the test if it consistently reports an error.
int retryCount = TestUtils.RETRY_COUNT;
int count = 0;
while(count < retryCount)
{
try
{
    // create a small file and upload it
    String rootPrefix = TestUtils.addPrefix("move-file-into-dir-" + count);
    int originalCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    File toRename = TestUtils.createTextFile(100);
    URI destFile = TestUtils.getUri(_testBucket, toRename, rootPrefix);
    S3File f = TestUtils.uploadFile(toRename, destFile);
    Assert.assertNotNull(f);
    Assert.assertEquals(
      originalCount + 1, TestUtils.listObjects(_testBucket, rootPrefix).size());

    // create simple directory structure and upload
    File top = TestUtils.createTmpDir(true);
    File a = TestUtils.createTextFile(top, 100);
    File b = TestUtils.createTextFile(top, 100);
    URI destDir = TestUtils.getUri(_testBucket, top, rootPrefix);
    List<S3File> uploaded = TestUtils.uploadDir(top, destDir);
    Assert.assertEquals(2, uploaded.size());
    int uploadCount = TestUtils.listObjects(_testBucket, rootPrefix).size();
    Assert.assertEquals(uploaded.size() + 1, uploadCount);

    // attempt the rename -- should move the file but leave previous files alone
    URI src = destFile;
    URI dest = destDir;
    RenameOptions opts = new RenameOptionsBuilder()
      .setSourceBucket(Utils.getBucket(src))
      .setSourceKey(Utils.getObjectKey(src))
      .setDestinationBucket(Utils.getBucket(dest))
      .setDestinationKey(Utils.getObjectKey(dest))
      .createRenameOptions();
    f = _client.rename(opts).get();
    Assert.assertNotNull(f);
    List<S3File> newObjs = TestUtils.listObjects(_testBucket, rootPrefix);
    Assert.assertEquals(uploadCount, newObjs.size());
    String topN = rootPrefix + "/" + top.getName() + "/";
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + a.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + b.getName()));
    Assert.assertTrue(TestUtils.findObject(newObjs, topN + toRename.getName()));
    
    return;
}
catch(Throwable t)
{
  ++count;
  if(count >= retryCount)
    throw t;
}
}
  }


  private synchronized void clearRetryCount()
  {
    _retryCount = 0;
  }


  private synchronized int getRetryCount()
  {
    return _retryCount;
  }


  private void checkUsageException(Exception ex, String expectedMsg)
    throws Exception
  {
    UsageException uex = null;
    if(ex instanceof UsageException)
    {
      uex = (UsageException) ex;
    }
    else
    {
      if((null != ex.getCause()) && (ex.getCause() instanceof UsageException))
        uex = (UsageException) ex.getCause();
    }

    if(null == uex)
      throw ex;
    else
      Assert.assertTrue(uex.getMessage().contains(expectedMsg));
  }


}