package com.logicblox.s3lib;

import java.util.Map;
import java.util.concurrent.Callable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;

public class MultipartAmazonUploadFactory implements UploadFactory {
	private AmazonS3 client;
	private ListeningExecutorService executor;

	public MultipartAmazonUploadFactory(AmazonS3 client, ListeningExecutorService executor) {
		this.client = client;
		this.executor = executor;
	}

	public ListenableFuture<Upload> startUpload(String bucketName, String key, Map<String,String> meta) {
		return executor.submit(new StartCallable(bucketName, key, meta));
	}

	private class StartCallable implements Callable<Upload> {
		private String key;
		private String bucketName;
		private Map<String,String> meta;

		public StartCallable(String bucketName, String key, Map<String,String> meta) {
			this.bucketName = bucketName;
			this.key = key;
			this.meta = meta;
		}

		public Upload call() throws Exception {
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setUserMetadata(meta);
			InitiateMultipartUploadRequest req = new InitiateMultipartUploadRequest(bucketName, key, metadata);
			InitiateMultipartUploadResult res = client.initiateMultipartUpload(req);
			return new MultipartAmazonUpload(client, bucketName, key, res.getUploadId(), executor);
		}
	}
}