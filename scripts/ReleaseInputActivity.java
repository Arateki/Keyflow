package com.keyflow.smoke;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Campo controlado pelo smoke: cada abertura começa vazia, sem estado dos Ajustes do sistema. */
public final class ReleaseInputActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(24, 160, 24, 24);
        EditText field = new EditText(this);
        field.setId(android.R.id.edit);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(field, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        field.requestFocus();
    }
}
