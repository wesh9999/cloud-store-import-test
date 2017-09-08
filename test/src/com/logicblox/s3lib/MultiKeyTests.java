package com.logicblox.s3lib;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.s3.model.ObjectMetadata;
import junit.framework.Assert;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


public class MultiKeyTests
{
  private static String _testBucket = null;
  private static CloudStoreClient _client = null;


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
  }


  private void createKey(String keyName, File keyDir)
    throws Throwable
  {
    KeyGenCommand kgc = new KeyGenCommand("RSA", 2048);
    File kf = new File(keyDir, keyName + ".pem");
    kgc.savePemKeypair(kf);
  }
  

  @Test
  public void testBasicOperation()
    throws Throwable
  {
    // generate 2 new public/private key pairs
    File keydir = TestUtils.createTmpDir(true);
    String[] keys = {"cloud-store-ut-1", "cloud-store-ut-2",
                     "cloud-store-ut-3", "cloud-store-ut-4"};

    for(String key : keys)
      createKey(key, keydir);
    TestUtils.setKeyProvider(keydir);

    // capture files currently in test bucket
    String rootPrefix = TestUtils.addPrefix("");
    List<S3File> objs = TestUtils.listObjects(_testBucket, rootPrefix);
    int originalCount = objs.size();

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, keys[0]);
    Assert.assertNotNull(f);

    // make sure file was uploaded
    objs = TestUtils.listObjects(_testBucket, rootPrefix);
    Assert.assertEquals(originalCount + 1, objs.size());
    String objKey = rootPrefix + toUpload.getName();
    Assert.assertTrue(TestUtils.findObject(objs, objKey));

    // download the file and compare it with the original
    File dlTemp = TestUtils.createTmpFile();
    f = TestUtils.downloadFile(dest, dlTemp);
    Assert.assertNotNull(f.getLocalFile());
    Assert.assertTrue(dlTemp.exists());
    Assert.assertTrue(TestUtils.compareFiles(toUpload, f.getLocalFile()));
    dlTemp.delete();

    // add the rest of the keys to the file
    for(int i = 1; i < keys.length; i++)
    {
      f = _client.addEncryptionKey(_testBucket, objKey, keys[i]).get();
      Assert.assertNotNull(f);
    }

    // hide all .pem files
    File hidden = TestUtils.createTmpDir(true);
    for(String key : keys)
    {
        String fn = key + ".pem";
        TestUtils.moveFile(fn, keydir, hidden);
    }

    // dl should fail
    String msg = null;
    dlTemp = TestUtils.createTmpFile();
    try
    {
      TestUtils.downloadFile(dest, dlTemp);
      msg = "Expected download error (key not found)";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("No eligible private key found"));
    }
    Assert.assertNull(msg);
    Assert.assertFalse(dlTemp.exists());

    int []keyOrder = {2, 0, 3};
    for(int ki : keyOrder)
    {
      // bring back specific .pem file, dl should succeed
      String fn = keys[ki] + ".pem";
      TestUtils.copyFile(fn, hidden, keydir);
      dlTemp = TestUtils.createTmpFile();
      f = TestUtils.downloadFile(dest, dlTemp);
      Assert.assertNotNull(f.getLocalFile());
      Assert.assertTrue(dlTemp.exists());
      Assert.assertTrue(TestUtils.compareFiles(toUpload, f.getLocalFile()));
      dlTemp.delete();

      // remove key from the file, dl should fail
      f = _client.removeEncryptionKey(_testBucket, objKey, keys[ki]).get();
      Assert.assertNotNull(f);
      dlTemp = TestUtils.createTmpFile();
      try
      {
        TestUtils.downloadFile(dest, dlTemp);
        msg = "Expected download error (key not found)";
      }
      catch(Throwable t)
      {
        // expected
        if (ki == 3)
          Assert.assertTrue(t.getMessage().contains("is not available to decrypt"));
        else
          Assert.assertTrue(t.getMessage().contains("No eligible private key found"));
      }
      Assert.assertNull(msg);
      Assert.assertFalse(dlTemp.exists());

      Files.delete((new File(keydir, fn)).toPath());
    }

    // use key1, dl should work
    TestUtils.copyFile(keys[1] + ".pem", hidden, keydir);
    f = TestUtils.downloadFile(dest, dlTemp);
    Assert.assertNotNull(f.getLocalFile());
    Assert.assertTrue(dlTemp.exists());
    Assert.assertTrue(TestUtils.compareFiles(toUpload, f.getLocalFile()));
    dlTemp.delete();
  }

  @Test
  public void testPartialKeys()
    throws Throwable
  {
    // generate a new public/private key pair
    String key1 = "cloud-store-ut-1";
    String key2 = "cloud-store-ut-2";
    File keydir = TestUtils.createTmpDir(true);
    String[] keys1 = TestUtils.createEncryptionKey(keydir, key1);
    String privateKey1 = keys1[0];
    String publicKey1 = keys1[1];
    String[] keys2 = TestUtils.createEncryptionKey(keydir, key2);
    String privateKey2 = keys2[0];
    String publicKey2 = keys2[1];
    TestUtils.setKeyProvider(keydir);

    String rootPrefix = TestUtils.addPrefix("");

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // the private part of key1 & the public part of key2 should be
    // sufficient to add key2
    String key1FileName = key1 + ".pem";
    TestUtils.writeToFile(privateKey1, new File(keydir, key1FileName));

    String key2FileName = key2 + ".pem";
    TestUtils.writeToFile(publicKey2, new File(keydir, key2FileName));

    String objKey = rootPrefix + toUpload.getName();
    f = _client.addEncryptionKey(_testBucket, objKey, key2).get();
    Assert.assertNotNull(f);

    // try to decrypt with key2's private part
    TestUtils.writeToFile(privateKey2, new File(keydir, key2FileName));
    File hidden = TestUtils.createTmpDir(true);
    TestUtils.moveFile(key1FileName, keydir, hidden);

    File dlTemp = TestUtils.createTmpFile();
    f = TestUtils.downloadFile(dest, dlTemp);
    Assert.assertNotNull(f.getLocalFile());
    Assert.assertTrue(dlTemp.exists());
    Assert.assertTrue(TestUtils.compareFiles(toUpload, f.getLocalFile()));
    dlTemp.delete();
  }

  @Test
  public void testDuplicateKey()
    throws Throwable
  {
    // generate public/private key
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    createKey(key1, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // test adding a key that is already used by the file
    String msg = null;
    try
    {
      _client.addEncryptionKey(_testBucket, objKey, key1).get();
      msg = "Expected error adding key";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("already exists"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testAddMissingKey()
    throws Throwable
  {
    // generate public/private key
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    String key2 = "cloud-store-ut-2";
    createKey(key1, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // test adding key that doesn't exist
    String msg = null;
    try
    {
      _client.addEncryptionKey(_testBucket, objKey, key2).get();
      msg = "Expected error adding key";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("Missing encryption key"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testRemoveMissingKey()
    throws Throwable
  {
    // generate public/private key
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    String key2 = "cloud-store-ut-2";
    createKey(key1, keydir);
    createKey(key2, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // test removing key that doesn't exist for encrypted file
    String msg = null;
    try
    {
      _client.removeEncryptionKey(_testBucket, objKey, key2).get();
      msg = "Expected error removing key";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("Cannot remove the last remaining key"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testUnencryptedAddKey()
    throws Throwable
  {
    // generate public/private key
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    createKey(key1, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, dest);
    Assert.assertNotNull(f);

    // test add key for unencrypted file
    String msg = null;
    try
    {
      _client.addEncryptionKey(_testBucket, objKey, key1).get();
      msg = "Expected exception";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("Object doesn't seem to be encrypted"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testUnencryptedRemoveKey()
    throws Throwable
  {
    // generate public/private key
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    createKey(key1, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadFile(toUpload, dest);
    Assert.assertNotNull(f);

    // test removing key for unencrypted file
    String msg = null;
    try
    {
      _client.removeEncryptionKey(_testBucket, objKey, key1).get();
      msg = "Expected error removing key";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("Object doesn't seem to be encrypted"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testRemoveLastKey()
    throws Throwable
  {
    // generate public/private keys
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    String key2 = "cloud-store-ut-2";
    createKey(key1, keydir);
    createKey(key2, keydir);
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // add the second key
    f = _client.addEncryptionKey(_testBucket, objKey, key2).get();
    Assert.assertNotNull(f);

    // removing first key should be OK
    f = _client.removeEncryptionKey(_testBucket, objKey, key1).get();
    Assert.assertNotNull(f);

    // removing last key should fail
    String msg = null;
    try
    {
      _client.removeEncryptionKey(_testBucket, objKey, key2).get();
      msg = "Expected error removing key";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("Cannot remove the last remaining key"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testMaxKeys()
    throws Throwable
  {
    // generate public/private keys
    File keydir = TestUtils.createTmpDir(true);
    int maxKeys = 4;
    int keyCount = maxKeys + 1;
    String[] keys = new String[keyCount];
    for(int i = 0; i < keyCount; ++i)
    {
      keys[i] = "cloud-store-ut-" + i;
      createKey(keys[i], keydir);
    }
    TestUtils.setKeyProvider(keydir);

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    String rootPrefix = TestUtils.addPrefix("");
    String objKey = rootPrefix + toUpload.getName();
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, keys[0]);
    Assert.assertNotNull(f);

    // should be OK
    for(int i = 1; i < maxKeys; ++i)
      _client.addEncryptionKey(_testBucket, objKey, keys[i]).get();

    // one more should fail
    String msg = null;
    try
    {
      _client.addEncryptionKey(_testBucket, objKey, keys[maxKeys]).get();
      msg = "Expected exception";
    }
    catch(Throwable t)
    {
      // expected
      Assert.assertTrue(t.getMessage().contains("No more than 4 keys are allowed"));
    }
    Assert.assertNull(msg);
  }


  @Test
  public void testDownloadNoPubkeyHash()
    throws Throwable
  {
    // generate 2 new public/private key pairs
    File keydir = TestUtils.createTmpDir(true);
    String key1 = "cloud-store-ut-1";
    createKey(key1, keydir);
    TestUtils.setKeyProvider(keydir);
    String rootPrefix = TestUtils.addPrefix("");

    // create a small file and upload
    File toUpload = TestUtils.createTextFile(100);
    URI dest = TestUtils.getUri(_testBucket, toUpload, rootPrefix);
    S3File f = TestUtils.uploadEncryptedFile(toUpload, dest, key1);
    Assert.assertNotNull(f);

    // remove "s3tool-pubkey-hash" from metadata to simulate files
    // uploaded by older cloud-store versions
    String objKey = rootPrefix + toUpload.getName();
    ObjectMetadata destMeta = _client.exists(dest).get();
    Assert.assertNotNull(destMeta);
    Map<String,String> destUserMeta = destMeta.getUserMetadata();
    Assert.assertNotNull(destUserMeta);
    Assert.assertTrue(destUserMeta.containsKey("s3tool-pubkey-hash"));
    destUserMeta.remove("s3tool-pubkey-hash");
    TestUtils.updateObjectUserMetadata(_testBucket, objKey, destUserMeta);

    // make sure "s3tool-pubkey-hash" has been removed
    destMeta = _client.exists(dest).get();
    Assert.assertNotNull(destMeta);
    destUserMeta = destMeta.getUserMetadata();
    Assert.assertNotNull(destUserMeta);
    Assert.assertFalse(destUserMeta.containsKey("s3tool-pubkey-hash"));

    // download the file and compare it with the original
    File dlTemp = TestUtils.createTmpFile();
    f = TestUtils.downloadFile(dest, dlTemp);
    Assert.assertNotNull(f.getLocalFile());
    Assert.assertTrue(dlTemp.exists());
    Assert.assertTrue(TestUtils.compareFiles(toUpload, f.getLocalFile()));
    dlTemp.delete();
  }


}
