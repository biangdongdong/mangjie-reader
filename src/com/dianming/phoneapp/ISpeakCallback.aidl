package com.dianming.phoneapp;

oneway interface ISpeakCallback {
    void onFinished(int utteranceId, int finishType);
}