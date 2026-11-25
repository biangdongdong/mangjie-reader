package com.dianming.phoneapp;

interface IIme {

    boolean isFullScreenOn();

    void requestFullScreenOn();

    void onVoiceClosed();

    boolean shouldTouchExplorationPassthroughRegion();

}