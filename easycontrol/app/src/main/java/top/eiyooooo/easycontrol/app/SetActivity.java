package top.eiyooooo.easycontrol.app;

import android.app.Activity;
import android.os.Bundle;

import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.databinding.ActivitySetBinding;
import top.eiyooooo.easycontrol.app.helper.SettingsPanelHelper;

public class SetActivity extends Activity {
  private ActivitySetBinding setActivity;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    PublicTools.setStatusAndNavBar(this);
    PublicTools.setLocale(this);
    setActivity = ActivitySetBinding.inflate(this.getLayoutInflater());
    setContentView(setActivity.getRoot());
    // 设置页面
    drawUi();
    setButtonListener();
  }

  // 设置默认值
  private void drawUi() {
    SettingsPanelHelper.populateAll(this, setActivity.setDefault, setActivity.setDisplay, setActivity.setOther, setActivity.setAbout);
  }

  // 设置按钮监听
  private void setButtonListener() {
    setActivity.backButton.setOnClickListener(v -> finish());
  }
}
