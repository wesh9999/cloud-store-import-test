package com.logicblox;

import com.logicblox.s3lib.*;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.services.s3.AmazonS3Client;

public class S3downloader {

	private static ListeningExecutorService getHttpExecutor() {
		int maxConcurrentConnections = 10;
		return MoreExecutors.listeningDecorator(Executors
				.newFixedThreadPool(maxConcurrentConnections));
	}

	private static ListeningScheduledExecutorService getInternalExecutor() {
		return MoreExecutors.listeningDecorator(Executors
				.newScheduledThreadPool(50));
	}

	private static KeyProvider getKeyProvider() {
		String encKeyDirectory = Utils.getDefaultKeyDirectory();
		File dir = new File(encKeyDirectory);
		if (!dir.exists() && !dir.mkdirs())
			throw new UsageException("specified key directory '"
					+ encKeyDirectory + "' does not exist");

		if (!dir.isDirectory())
			throw new UsageException("specified key directory '"
					+ encKeyDirectory + "' is not a directory");

		return new DirectoryKeyProvider(dir);
	}

	protected static URI getURI(List<String> urls) throws URISyntaxException {
		if (urls.size() != 1)
			throw new UsageException("A single S3 object URL is required");
		return Utils.getURI(urls.get(0));
	}

	protected static String getBucket(List<String> urls)
			throws URISyntaxException {
		return Utils.getBucket(getURI(urls));
	}

	protected static String getObjectKey(List<String> urls)
			throws URISyntaxException {
		return Utils.getObjectKey(getURI(urls));
	}

	private static void download(S3Client client, List<String> urls)
			throws Exception {
		String file = "test.gz";
		File output = new File(file);
		ListenableFuture<?> result;
		boolean recursive = false;
		boolean overwrite = true;

		if (getObjectKey(urls).endsWith("/")) {
			result = client.downloadDirectory(output, getURI(urls), recursive,
					overwrite);
		} else {
			// Test if S3 url exists.
			if (client.exists(getBucket(urls), getObjectKey(urls)).get() == null) {
				throw new UsageException("Object not found at " + getURI(urls));
			}
			result = client.download(output, getURI(urls));
		}

		try {
			result.get();
		} catch (ExecutionException exc) {
			rethrow(exc.getCause());
		}

		client.shutdown();
	}

	private static void rethrow(Throwable thrown) throws Exception {
		if (thrown instanceof Exception)
			throw (Exception) thrown;
		if (thrown instanceof Error)
			throw (Error) thrown;
		else
			throw new RuntimeException(thrown);
	}

	public static void main(String[] args) throws Exception {
		ClientConfiguration clientCfg = new ClientConfiguration();
		clientCfg.setProtocol(Protocol.HTTPS);

	
		AmazonS3Client s3Client = new AmazonS3Client(clientCfg);

		long chunkSize = Utils.getDefaultChunkSize();

		S3Client client = new S3Client(s3Client, getHttpExecutor(),
				getInternalExecutor(), chunkSize, getKeyProvider());
		List<String> urls = new ArrayList<String>();
		urls.add("s3://kiabi-fred-dev/fmachine/test.gz");
		download(client, urls);
		client.shutdown();
	}
}