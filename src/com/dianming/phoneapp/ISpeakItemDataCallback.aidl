package com.dianming.phoneapp;

oneway interface ISpeakItemDataCallback {
    void audioAvailable(in byte[] buffer, int offset, int length);
    void done();
}
