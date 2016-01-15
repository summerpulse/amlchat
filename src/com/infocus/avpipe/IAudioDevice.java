/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        IAudioDevice.java
**
**  PURPOSE     Interface to audio input/output device
**
**==========================================================================*/

package com.infocus.avpipe;

/**
 * Discription Here
 * <p>
 */
public interface IAudioDevice {
	/**
	 * Init the audio device
	 * 
	 * @param info
	 */
	public void open(AudioFormatInfo info);

	/**
	 * start the audio device
	 */
	public void start();

	/**
	 * stop audio device
	 */
	public void stop();

	/**
	 * close and release recources
	 */
	public void close();

	/**
	 * get the device info
	 * 
	 * @param devId
	 * @return
	 */
	public DevInfo getDevInfo(int devId);

	public void setAudioMute(boolean isMute);

    /**
	 * Get the buffering requirements of the audio device based on the format given.
	 * 
	 * @param info
	 * @return Negative return values indicate an invalid configuration.
	 */
    public int getBufferSize(AudioFormatInfo info);

    /**
	 * Set the buffer sized used with the device.
	 * 
	 * @param info
	 * @return
	 */
    public int setBufferSize(int bufferSize);
}
