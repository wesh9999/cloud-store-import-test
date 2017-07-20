package com.logicblox.s3lib;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

import com.google.common.base.Optional;
import org.apache.commons.codec.digest.DigestUtils;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.FutureFallback;

public class DownloadCommand extends Command
{
  private ListeningExecutorService _downloadExecutor;
  private ListeningScheduledExecutorService _executor;
  private KeyProvider _encKeyProvider;
  private ConcurrentMap<Integer, byte[]> etags = new ConcurrentSkipListMap<Integer, byte[]>();
  private Optional<OverallProgressListenerFactory> progressListenerFactory;

  public DownloadCommand(
    ListeningExecutorService downloadExecutor,
    ListeningScheduledExecutorService internalExecutor,
    File file,
    boolean overwrite,
    KeyProvider encKeyProvider,
    OverallProgressListenerFactory progressListenerFactory)
  throws IOException
  {
    _downloadExecutor = downloadExecutor;
    _executor = internalExecutor;
    _encKeyProvider = encKeyProvider;

    this.file = file;
    createNewFile(overwrite);
    this.progressListenerFactory = Optional.fromNullable
        (progressListenerFactory);
  }

  private void createNewFile(boolean overwrite) throws IOException
  {
    file = file.getAbsoluteFile();
    File dir = file.getParentFile();
    if(!dir.exists())
    {
      if(!dir.mkdirs())
        throw new IOException("Could not create directory '" + dir + "'");
    }

    if(file.exists())
    {
      if(overwrite)
      {
        if(!file.delete())
          throw new IOException("Could not delete existing file '" + file + "'");
      }
      else
      {
        throw new UsageException("File '" + file 
          + "' already exists.  Please delete or use --overwrite");
      }
    }

    if(!file.createNewFile())
      throw new IOException("File '" + file + "' already exists");
  }

  public ListenableFuture<S3File> run(final String bucket, final String key, final String version)
  {
    ListenableFuture<AmazonDownload> download = startDownload(bucket, key, version);
    download = Futures.transform(download, startPartsAsyncFunction());
    download = Futures.transform(download, validate());
    ListenableFuture<S3File> res = Futures.transform(
      download,
      new Function<AmazonDownload, S3File>()
      {
        public S3File apply(AmazonDownload download)
        {
          S3File f = new S3File();
          f.setLocalFile(DownloadCommand.this.file);
          f.setETag(download.getETag());
          f.setBucketName(bucket);
          f.setKey(key);
          return f;
        }
      }
    );

    return Futures.withFallback(
      res,
      new FutureFallback<S3File>()
      {
        public ListenableFuture<S3File> create(Throwable t)
        {
          if(DownloadCommand.this.file.exists())
            DownloadCommand.this.file.delete();

          if (t instanceof UsageException) {
            return Futures.immediateFailedFuture(t);
          }
          return Futures.immediateFailedFuture(new Exception("Error " +
              "downloading " + getUri(bucket, key)+ ".", t));
        }
      });
  }

  /**
   * Step 1: Start download and fetch metadata.
   */
  private ListenableFuture<AmazonDownload> startDownload(final String bucket, final String key, final String version )
  {
    return executeWithRetry(
      _executor,
      new Callable<ListenableFuture<AmazonDownload>>()
      {
        public ListenableFuture<AmazonDownload> call()
        {
          return startDownloadActual(bucket, key, version);
        }

        public String toString()
        {
          String toStringOutput = "starting download " + bucket + "/" + key;
          if (version != null) {
            return toStringOutput + " version id = " + version;
          }
          return toStringOutput;
        }
      });
  }

  private ListenableFuture<AmazonDownload> startDownloadActual(final String bucket, final String key, final String version)
  {
    AmazonDownloadFactory factory = new AmazonDownloadFactory(getAmazonS3Client(), _downloadExecutor);

    AsyncFunction<AmazonDownload, AmazonDownload> initDownload = new AsyncFunction<AmazonDownload, AmazonDownload>()
    {
      public ListenableFuture<AmazonDownload> apply(AmazonDownload download)
      {
        Map<String, String> meta = download.getMeta();

        String errPrefix = getUri(download.getBucket(), download.getKey()) + ": ";
        long len = download.getLength();
        long cs = chunkSize;
        if (meta.containsKey("s3tool-version"))
        {
          String objectVersion = meta.get("s3tool-version");

          if (!String.valueOf(Version.CURRENT).equals(objectVersion))
          {
            throw new UsageException(
                errPrefix + "file uploaded with unsupported version: " +
                    objectVersion + ", should be " + Version.CURRENT);
          }
          if (meta.containsKey("s3tool-key-name"))
          {
            if (_encKeyProvider == null)
              throw new UsageException(errPrefix + "No encryption key " +
                                       "provider is specified");
            String keyName = meta.get("s3tool-key-name");
            String keyNamesStr = meta.get("s3tool-key-name");
            List<String> keyNames = new ArrayList<>(Arrays.asList(
              keyNamesStr.split(",")));
            String symKeyStr;
            PrivateKey privKey = null;
            if (keyNames.size() == 1)
            {
              // We handle objects with a single encryption key separately
              // because it's allowed not to have "s3tool-pubkey-hash" header
              // (for backwards compatibility)
              try
              {
                privKey = _encKeyProvider.getPrivateKey(keyName);
                if (meta.containsKey("s3tool-pubkey-hash"))
                {
                  String pubKeyHashHeader = meta.get("s3tool-pubkey-hash");
                  PublicKey pubKey = Command.getPublicKey(privKey);
                  String pubKeyHashLocal = DatatypeConverter.printBase64Binary(
                    DigestUtils.sha256(pubKey.getEncoded())).substring(0, 8);

                  if (!pubKeyHashLocal.equals(pubKeyHashHeader))
                  {
                    throw new UsageException(
                      "Public-key checksums do not match. " +

                      "Calculated hash: " + pubKeyHashLocal +
                      ", Expected hash: " + pubKeyHashHeader);
                  }
                }
              }
              catch (NoSuchKeyException e)
              {
                throw new UsageException(errPrefix + "private key '" + keyName
                    + "' is not available to decrypt");
              }
              symKeyStr = meta.get("s3tool-symmetric-key");
            }
            else
            {
              // Objects with multiple encryption keys must have
              // "s3tool-pubkey-hash" header. We might want to relax this
              // requirement.
              if (!meta.containsKey("s3tool-pubkey-hash"))
              {
                throw new UsageException(errPrefix + " public key hashes are " +
                                         "required when object has multiple " +
                                         "encryption keys");
              }
              String pubKeyHashHeadersStr = meta.get("s3tool-pubkey-hash");
              List<String> pubKeyHashHeaders = new ArrayList<>(Arrays.asList(
                pubKeyHashHeadersStr.split(",")));
              int privKeyIndex = -1;
              boolean privKeyFound = false;
              for (String kn : keyNames)
              {
                privKeyIndex++;
                try
                {
                  privKey = _encKeyProvider.getPrivateKey(kn);
                }
                catch (NoSuchKeyException e)
                {
                  // We might find an eligible key later.
                  continue;
                }

                try
                {
                  PublicKey pubKey = Command.getPublicKey(privKey);
                  String pubKeyHashLocal = DatatypeConverter.printBase64Binary(
                    DigestUtils.sha256(pubKey.getEncoded())).substring(0, 8);

                  if (pubKeyHashLocal.equals(pubKeyHashHeaders.get(privKeyIndex)))
                  {
                    // Successfully-read, validated key.
                    privKeyFound = true;
                    break;
                  }
                }
                catch (NoSuchKeyException e)
                {
                  throw new UsageException(errPrefix + "Cannot generate the " +
                                           "public key out of the private one" +
                                           " for " + kn);
                }
              }

              if (privKey == null || !privKeyFound)
              {
                // No private key found
                throw new UsageException(errPrefix + "No eligible private key" +
                                         " found");
              }
              List<String> symKeys = new ArrayList<>(Arrays.asList(
                meta.get("s3tool-symmetric-key").split(",")));
              symKeyStr = symKeys.get(privKeyIndex);
            }

            Cipher cipher;
            byte[] encKeyBytes;
            try
            {
              cipher = Cipher.getInstance("RSA");
              cipher.init(Cipher.DECRYPT_MODE, privKey);
              encKeyBytes = cipher.doFinal(DatatypeConverter.parseBase64Binary(
                symKeyStr));
            }
            catch (NoSuchAlgorithmException e)
            {
              throw new RuntimeException(e);
            }
            catch (NoSuchPaddingException e)
            {
              throw new RuntimeException(e);
            }
            catch (InvalidKeyException e)
            {
              throw new RuntimeException(e);
            }
            catch (IllegalBlockSizeException e)
            {
              throw new RuntimeException(e);
            }
            catch (BadPaddingException e)
            {
              throw new RuntimeException(e);
            }

            encKey = new SecretKeySpec(encKeyBytes, "AES");
          }

          cs = Long.valueOf(meta.get("s3tool-chunk-size"));
          len = Long.valueOf(meta.get("s3tool-file-length"));
        }

        setFileLength(len);
        if (cs == 0)
          cs = Utils.getDefaultChunkSize(len);
        setChunkSize(cs);
        return Futures.immediateFuture(download);
      }
    };

    return Futures.transform(factory.startDownload(bucket, key, version), initDownload);
  }

  /**
   * Step 2: Start downloading parts
   */
  private AsyncFunction<AmazonDownload, AmazonDownload> startPartsAsyncFunction()
  {
    return new AsyncFunction<AmazonDownload, AmazonDownload>()
    {
      public ListenableFuture<AmazonDownload> apply(AmazonDownload download) throws Exception
      {
        return startParts(download);
      }
    };
  }

  private ListenableFuture<AmazonDownload> startParts(AmazonDownload download)
  throws IOException, UsageException
  {
    OverallProgressListener opl = null;
    if (progressListenerFactory.isPresent()) {
      opl = progressListenerFactory.get().create(
          new ProgressOptionsBuilder()
              .setObjectUri(getUri(download.getBucket(), download.getKey()))
              .setOperation("download")
              .setFileSizeInBytes(fileLength)
              .createProgressOptions());
    }

    List<ListenableFuture<Integer>> parts = new ArrayList<ListenableFuture<Integer>>();
    for (long position = 0;
         position < fileLength || (position == 0 && fileLength == 0);
         position += chunkSize)
    {
      parts.add(startPartDownload(download, position, opl));
    }

    return Futures.transform(Futures.allAsList(parts), Functions.constant(download));
  }

  private ListenableFuture<Integer> startPartDownload(final AmazonDownload
                                                          download,
                                                      final long position,
                                                      final OverallProgressListener opl)
  {
    final int partNumber = (int) (position / chunkSize);

    return executeWithRetry(
      _executor,
      new Callable<ListenableFuture<Integer>>()
      {
        public ListenableFuture<Integer> call()
        {
          return startPartDownloadActual(download, position, opl);
        }

        public String toString()
        {
          return "downloading part " + (partNumber + 1);
        }
      });
  }

  private ListenableFuture<Integer> startPartDownloadActual(final AmazonDownload download,
                                                            final long position,
                                                            OverallProgressListener opl)
  {
    final int partNumber = (int) (position / chunkSize);
    long start;
    long partSize;

    if (encKey != null)
    {
      long blockSize;
      try
      {
        blockSize = Cipher.getInstance("AES/CBC/PKCS5Padding").getBlockSize();
      }
      catch (NoSuchAlgorithmException e)
      {
        throw new RuntimeException(e);
      }
      catch (NoSuchPaddingException e)
      {
        throw new RuntimeException(e);
      }

      long postCryptSize = Math.min(fileLength - position, chunkSize);
      start = partNumber * blockSize * (chunkSize/blockSize + 2);
      partSize = blockSize * (postCryptSize/blockSize + 2);
    }
    else
    {
      start = position;
      partSize = Math.min(fileLength - position, chunkSize);
    }

    ListenableFuture<InputStream> getPartFuture = download.getPart(start,
        start + partSize - 1, Optional.fromNullable(opl));

    AsyncFunction<InputStream, Integer> readDownloadFunction = new AsyncFunction<InputStream, Integer>()
    {
      public ListenableFuture<Integer> apply(InputStream stream) throws Exception
      {
        try
        {
          readDownload(download, stream, position, partNumber);
          return Futures.immediateFuture(partNumber);
        } finally {
          // make sure that the stream is always closed
          try
          {
            stream.close();
          }
          catch (IOException e) {}
        }
      }
    };

    return Futures.transform(getPartFuture, readDownloadFunction);
  }

  private void readDownload(AmazonDownload download, InputStream inStream, long position, int partNumber)
  throws Exception
  {
    HashingInputStream stream = new HashingInputStream(inStream);
    RandomAccessFile out = new RandomAccessFile(file, "rw");
    out.seek(position);

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

    InputStream in;
    if (encKey != null)
    {
      in = new CipherWithInlineIVInputStream(stream, cipher, Cipher.DECRYPT_MODE, encKey);
    }
    else
    {
      in = stream;
    }

    int postCryptSize = (int) Math.min(fileLength - position, chunkSize);
    int bufSize = 8192;
    int offset = 0;
    byte[] buf = new byte[bufSize];

    Runnable cleanup = () ->
      {
        try
        {
          out.close();
          stream.close();
        }
        catch (IOException ignored) {}
      };

    // Handle empty encrypted file, offset == postCryptSize is implied
    if (encKey != null && postCryptSize == 0)
    {
      int result = readSafe(in, buf, 0, 0, cleanup);
      if (result != -1)
      {
        // TODO: Check if the correct/expected result here should be 0 (instead
        // of -1).
        cleanup.run();
        throw new IOException("EOF was expected");
      }
    }
    else // Not necessary, just for easier reading
    {
      while (offset < postCryptSize)
      {
        int len = Math.min(bufSize, postCryptSize - offset);
        int result = readSafe(in, buf, 0, len, cleanup);
        if (result == -1)
        {
          cleanup.run();
          throw new IOException("unexpected EOF");
        }

        writeSafe(out, buf, 0, result, cleanup);
        offset += result;
      }
    }

    cleanup.run();
    etags.put(partNumber, stream.getDigest());
  }

  private int readSafe(InputStream in, byte[] buf, int offset, int len,
                       Runnable cleanup)
  throws IOException
  {
    int result;
    try
    {
      result = in.read(buf, offset, len);
    }
    catch (IOException e)
    {
      cleanup.run();
      throw e;
    }
    return result;
  }

  private void writeSafe(RandomAccessFile out, byte[] buf, int offset, int len,
                         Runnable cleanup)
  throws IOException
  {
    try
    {
      out.write(buf, offset, len);
    }
    catch (IOException e)
    {
      cleanup.run();
      throw e;
    }
  }

  private AsyncFunction<AmazonDownload, AmazonDownload> validate()
  {
    return new AsyncFunction<AmazonDownload, AmazonDownload>()
    {
      public ListenableFuture<AmazonDownload> apply(AmazonDownload download)
      {
        return validateChecksum(download);
      }
    };
  }

  private ListenableFuture<AmazonDownload> validateChecksum(final AmazonDownload download)
  {
    ListenableFuture<AmazonDownload> result =
        _executor.submit(new Callable<AmazonDownload>()
        {
          public AmazonDownload call() throws Exception
          {
            String remoteEtag = download.getETag();
            String localDigest = "";
            String fn = "'s3://" + download.getBucket() + "/" + download.getKey() + "'";

            if(null == remoteEtag)
            {
              System.err.println("Warning: Skipped checksum validation for " + 
                fn + ".  No etag attached to object.");
              return download;
            }

            if ((remoteEtag.length() > 32) &&
                (remoteEtag.charAt(32) == '-')) {
              // Object has been uploaded using S3's multipart upload protocol,
              // so it has a special Etag documented here:
              // http://permalink.gmane.org/gmane.comp.file-systems.s3.s3tools/583
              
              // Since, the Etag value depends on the value of the chuck size (and 
              // the number of the chucks) we cannot validate robustly checksums for
              // files uploaded with tool other than s3tool.
              Map<String,String> meta = download.getMeta();
              if (!meta.containsKey("s3tool-version")) {
                System.err.println("Warning: Skipped checksum " +
                    "validation for " + fn + ". It was uploaded using " +
                    "other tool's multipart protocol.");
                return download;
              }

              int expectedPartsNum = fileLength == 0 ? 1 :
                  (int) Math.ceil(fileLength / (double) chunkSize);
              int actualPartsNum = Integer.parseInt(remoteEtag.substring(33));

              if (expectedPartsNum != actualPartsNum) {
                System.err.println("Warning: Skipped checksum validation for " + fn +
                    ". Actual number of parts: "  + actualPartsNum +
                    ", Expected number of parts: " + expectedPartsNum + 
                    ". Probably the ETag was changed by using another tool.");
                return download;
              }
                
              ByteArrayOutputStream os = new ByteArrayOutputStream();
              for (Integer pNum : etags.keySet()) {
                os.write(etags.get(pNum));
              }

              localDigest = DigestUtils.md5Hex(os.toByteArray()) + "-" + etags.size();
            }
            else {
              // Object has been uploaded using S3's simple upload protocol,
              // so its Etag should be equal to object's MD5.
              // Same should hold for objects uploaded to GCS (if "compose" operation
              // wasn't used).
              if (etags.size() == 1) {
                // Single-part download (1 range GET).
                localDigest = DatatypeConverter.printHexBinary(etags.get(0)).toLowerCase();
              }
              else {
                // Multi-part download (>1 range GETs).
                System.err.println("Warning: Skipped checksum validation for " + fn +
                    ". No efficient way to compute MD5 on multipart downloads of files with singlepart ETag.");
                return download;
              }
            }
            if(remoteEtag.equals(localDigest)) {
              return download;
            }
            else {
              throw new BadHashException("Failed checksum validation for " +
                  download.getBucket() + "/" + download.getKey() + ". " +
                  "Calculated MD5: " + localDigest +
                  ", Expected MD5: " + remoteEtag);
            }
          }
        });

    return result;
  }
}
