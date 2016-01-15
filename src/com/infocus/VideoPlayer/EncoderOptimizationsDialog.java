package com.infocus.VideoPlayer;

import java.util.ArrayList;
import java.util.List;

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.DialogFragment;
import android.os.Bundle;
import android.content.Context;
import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.Toast;
import android.util.Log;

import com.infocus.avpipe.AVTypes;

public class EncoderOptimizationsDialog extends DialogFragment implements OnClickListener {

    private static final String TAG = "EncoderOptimizationDialog";
    public static final String BACK_STACK_NAME = "encoder_optimizations_dialog";

    private RelativeLayout rlOptimizationBack;
    private RelativeLayout rlDummy; /* accepts focus after a textview doesn't want it anymore */

    /* SPS PPS */
    private CheckBox cbSPSPPS;

    /* Infinite IFrame */
    private CheckBox cbInfiniteIFrame;

    /* Macroblock Slice Spacing */
    private CheckBox cbMacroblockSlice;
    private EditText etMacroblockSlice;

    /* Intrarefresh Refresh */
    private CheckBox cbIntrarefresh;
    private EditText etIntrarefreshRefresh;
    private EditText etIntrarefreshOverlap;

    /* Bitrate */
    private CheckBox cbEncoderBitrate;
    private EditText etEncoderBitrate;
    private Button bEncoderBitrate;

    int mNum;
    int mStackLevel;

    /**
     * Create a new instance of EncoderOptimizationsDialogFragment, providing "num"
     * as an argument.
     */
    static EncoderOptimizationsDialog newInstance(int num) {
        EncoderOptimizationsDialog f = new EncoderOptimizationsDialog();

        // Supply num input as an argument.
        Bundle args = new Bundle();
        args.putInt("num", num);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNum = getArguments().getInt("num");

        // Pick a style based on the num.
        int style = DialogFragment.STYLE_NORMAL, theme = 0;
        switch ((mNum-1)%6) {
            case 1: style = DialogFragment.STYLE_NO_TITLE; break;
            case 2: style = DialogFragment.STYLE_NO_FRAME; break;
            case 3: style = DialogFragment.STYLE_NO_INPUT; break;
            case 4: style = DialogFragment.STYLE_NORMAL; break;
            case 5: style = DialogFragment.STYLE_NORMAL; break;
            case 6: style = DialogFragment.STYLE_NO_TITLE; break;
            case 7: style = DialogFragment.STYLE_NO_FRAME; break;
            case 8: style = DialogFragment.STYLE_NORMAL; break;
        }

         switch ((mNum-1)%6) {
            case 4: theme = android.R.style.Theme_Holo; break;
            case 5: theme = android.R.style.Theme_Holo_Light_Dialog; break;
            case 6: theme = android.R.style.Theme_Holo_Light; break;
            case 7: theme = android.R.style.Theme_Holo_Light_Panel; break;
            case 8: theme = android.R.style.Theme_Holo_Light; break;
        }
        setStyle(style, theme);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.developer_options_encoder_optimizations, container, false);

        rlDummy = (RelativeLayout) view.findViewById(R.id.rlDummy);
        rlOptimizationBack = (RelativeLayout) view.findViewById(R.id.rlOptimizationBack);
        rlOptimizationBack.setOnClickListener(this);

        AVTypes.EncoderOptimizations opts = ((VideoPlayerActivity)getActivity()).getVideoInputDevice().getEncoderOptimizations();

        /* SPS PPS */
        cbSPSPPS = (CheckBox) view.findViewById(R.id.cbOptimizationSPSPPS);
        cbSPSPPS.setOnClickListener(this);
        cbSPSPPS.setChecked(opts.sps_pps_enable);

        /* Infinite IFrame */
        cbInfiniteIFrame = (CheckBox) view.findViewById(R.id.cbOptimizationInfiniteIFrame);
        cbInfiniteIFrame.setOnClickListener(this);
        cbInfiniteIFrame.setChecked(opts.infinite_iframe_enable);

        /* Macroblock slice spacing */
        cbMacroblockSlice = (CheckBox) view.findViewById(R.id.cbOptimizationMacroblockSlice);
        cbMacroblockSlice.setOnClickListener(this);
        cbMacroblockSlice.setChecked(opts.macroblock_slice_enable);

        etMacroblockSlice = (EditText) view.findViewById(R.id.etOptimizationMacroblockSlice);
        etMacroblockSlice.setText(Integer.toString(opts.macroblock_slice_value));
        etMacroblockSlice.setEnabled(cbMacroblockSlice.isChecked());
        etMacroblockSlice.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                    rlDummy.requestFocus();
                    return true;
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    // prevent forwarding to MainActivity
                    return true;
                }
                return false;
            }
        });

        /* Intrarefresh */
        cbIntrarefresh = (CheckBox) view.findViewById(R.id.cbOptimizationIntrarefresh);
        cbIntrarefresh.setOnClickListener(this);
        cbIntrarefresh.setChecked(opts.intrarefresh_enable);

        // refresh
        etIntrarefreshRefresh = (EditText) view.findViewById(R.id.etOptimizationIntrarefreshRefresh);
        etIntrarefreshRefresh.setText(Integer.toString(opts.intrarefresh_refresh_value));
        etIntrarefreshRefresh.setEnabled(cbIntrarefresh.isChecked());
        etIntrarefreshRefresh.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                    rlDummy.requestFocus();
                    return true;
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    // prevent forwarding to MainActivity
                    return true;
                }
                return false;
            }
        });

        // overlap
        etIntrarefreshOverlap = (EditText) view.findViewById(R.id.etOptimizationIntrarefreshOverlap);
        etIntrarefreshOverlap.setText(Integer.toString(opts.intrarefresh_overlap_value));
        etIntrarefreshOverlap.setEnabled(cbIntrarefresh.isChecked());
        etIntrarefreshOverlap.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                    rlDummy.requestFocus();
                    return true;
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    // prevent forwarding to MainActivity
                    return true;
                }
                return false;
            }
        });

        /* Encoder bitrate */
        cbEncoderBitrate = (CheckBox) view.findViewById(R.id.cbOptimizationEncoderBitrate);
        cbEncoderBitrate.setOnClickListener(this);
        cbEncoderBitrate.setChecked(opts.encoder_bitrate_enable);

        etEncoderBitrate = (EditText) view.findViewById(R.id.etOptimizationEncoderBitrate);
        etEncoderBitrate.setText(Integer.toString(opts.encoder_bitrate_value));
        etEncoderBitrate.setEnabled(cbEncoderBitrate.isChecked());
        etEncoderBitrate.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                    rlDummy.requestFocus();
                    return true;
                }
                else if (event.getAction() == KeyEvent.ACTION_UP) {
                    // prevent forwarding to MainActivity
                    return true;
                }
                return false;
            }
        });

        bEncoderBitrate = (Button) view.findViewById(R.id.bOptimizationEncoderBitrate);
        bEncoderBitrate.setVisibility(cbEncoderBitrate.isChecked() ? View.VISIBLE : View.GONE);
        bEncoderBitrate.setOnClickListener(new View.OnClickListener() {
             public void onClick(View v) {
                 String bitrate =  etEncoderBitrate.getText().toString();
                 if (bitrate.isEmpty() || Integer.parseInt(bitrate) == 0) {
                     Toast.makeText(getActivity(), "Must set a positive number for bitrate", Toast.LENGTH_SHORT);
                 }

                 storeEncoderOptimizations();
             }
        });

        return view;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.rlOptimizationBack:
            storeEncoderOptimizations();
            dismiss();
            break;
        case R.id.cbOptimizationSPSPPS:
            break;
        case R.id.cbOptimizationInfiniteIFrame:
            break;
        case R.id.cbOptimizationMacroblockSlice:
            etMacroblockSlice.setEnabled(cbMacroblockSlice.isChecked());
            break;
        case R.id.cbOptimizationIntrarefresh:
            etIntrarefreshRefresh.setEnabled(cbIntrarefresh.isChecked());
            etIntrarefreshOverlap.setEnabled(cbIntrarefresh.isChecked());
            break;
        case R.id.cbOptimizationEncoderBitrate:
            etEncoderBitrate.setEnabled(cbEncoderBitrate.isChecked());
            bEncoderBitrate.setVisibility(cbEncoderBitrate.isChecked() ? View.VISIBLE : View.GONE);
            break;
        default:
            break;
        }
    }

    private void printOptimizations() {
        AVTypes.EncoderOptimizations opts = ((VideoPlayerActivity)getActivity()).getVideoInputDevice().getEncoderOptimizations();
        Log.v(TAG, "sps_pps enable: " + opts.sps_pps_enable);
        Log.v(TAG, "infinite_iframe enable: " + opts.infinite_iframe_enable);
        Log.v(TAG, "infinite_iframe enable: " + opts.infinite_iframe_enable);
        Log.v(TAG, "macroblock_slice enable: " + opts.macroblock_slice_enable);
        Log.v(TAG, "macroblock_slice value: " + opts.macroblock_slice_value);
        Log.v(TAG, "encoder_bitrate enable: " + opts.encoder_bitrate_enable);
        Log.v(TAG, "encoder_bitrate value: " + opts.encoder_bitrate_value);
        Log.v(TAG, "intrarefresh enable: " + opts.intrarefresh_enable);
        Log.v(TAG, "intrarefresh refresh: " + opts.intrarefresh_refresh_value);
        Log.v(TAG, "intrarefresh overlap: " + opts.intrarefresh_overlap_value);
    }

    private void storeEncoderOptimizations() {
        AVTypes.EncoderOptimizations opts = ((VideoPlayerActivity)getActivity()).getVideoInputDevice().getEncoderOptimizations();
        opts.sps_pps_enable = cbSPSPPS.isChecked();
        opts.infinite_iframe_enable = cbInfiniteIFrame.isChecked();
        opts.macroblock_slice_enable = cbMacroblockSlice.isChecked();
        opts.encoder_bitrate_enable = cbEncoderBitrate.isChecked();
        opts.intrarefresh_enable = cbIntrarefresh.isChecked();

        String val;
        val = etIntrarefreshRefresh.getText().toString();
        opts.intrarefresh_refresh_value = val.isEmpty() ? 0 : Integer.parseInt(val);

        val = etIntrarefreshOverlap.getText().toString();
        opts.intrarefresh_overlap_value = val.isEmpty() ? 0 : Integer.parseInt(val);

        val = etEncoderBitrate.getText().toString();
        opts.encoder_bitrate_value = val.isEmpty() ? 0 : Integer.parseInt(val);

        val = etMacroblockSlice.getText().toString();
        opts.macroblock_slice_value = val.isEmpty() ? 0 : Integer.parseInt(val);

        ((VideoPlayerActivity)getActivity()).getVideoInputDevice().setEncoderOptimizations(opts);
    }
}
