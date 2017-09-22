/*
  Copyright 2017, Infor Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package com.logicblox.cloudstore;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;

class GCSProgressListener
    implements MediaHttpUploaderProgressListener {
    final private OverallProgressListener opl;
    final private PartProgressEvent ppe;

    public GCSProgressListener(OverallProgressListener opl,
                               PartProgressEvent ppe) {
        this.opl = opl;
        this.ppe = ppe;
    }

    @Override
    public void progressChanged(MediaHttpUploader uploader) {
        switch (uploader.getUploadState()) {
            case MEDIA_IN_PROGRESS:
                // TODO: Progress works iff you have a content length specified.
                ppe.setTransferredBytes(uploader.getNumBytesUploaded());
                opl.progress(ppe);
                break;
            case MEDIA_COMPLETE:
                ppe.setTransferredBytes(uploader.getNumBytesUploaded());
                opl.progress(ppe);
                break;
            default:
                break;
        }
    }
}