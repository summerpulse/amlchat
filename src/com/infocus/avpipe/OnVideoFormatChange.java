/*
** Copyright (c) 2014  InFocus Corporation. All rights reserved.
*/
/*============================================================================
**
**  FILE        OnVideoFormatChange.java
**
**  PURPOSE     Notified by Avpipe when video parameters change
**
**==========================================================================*/

package com.infocus.avpipe;

/**
 * video format change callback
 * 
 */
public interface OnVideoFormatChange {

	/**
	 * When MediaCodec's MediaFormat Changed will call this method
	 * 
	 * @param width
	 * @param height
	 */
	public void onSizeChange(int width, int height);

}
