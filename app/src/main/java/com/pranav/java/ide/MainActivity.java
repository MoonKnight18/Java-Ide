package com.pranav.java.ide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.googlecode.d2j.smali.BaksmaliCmd;

import com.pranav.common.Indexer;
import com.pranav.common.util.ConcurrentUtil;
import com.pranav.common.util.FileUtil;
import com.pranav.common.util.ZipUtil;
import com.pranav.java.ide.compiler.CompileTask;
import com.pranav.java.ide.ui.TreeViewDrawer;
import com.pranav.java.ide.ui.treeview.helper.TreeCreateNewFileContent;
import com.pranav.lib_android.code.disassembler.*;
import com.pranav.lib_android.code.formatter.*;
import com.pranav.lib_android.task.JavaBuilder;

import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.textmate.core.internal.theme.reader.ThemeReader;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.widget.CodeEditor;

import org.benf.cfr.reader.Main;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;

public final class MainActivity extends AppCompatActivity {

	public CodeEditor editor;
	public DrawerLayout drawer;
	public JavaBuilder builder;
	public SharedPreferences prefs;

	private AlertDialog loadingDialog;
	private Thread runThread;
	private FragmentTransaction fragmentTransaction;
	// It's a variable that stores an object temporarily, for e.g. if you want to access a local
	// variable in a lambda expression, etc.
	private String temp;

	public String currentWorkingFilePath;
	public Indexer indexer;
	public static String BUILD_STATUS = "BUILD_STATUS";

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		prefs = getSharedPreferences("compiler_settings", MODE_PRIVATE);

		editor = findViewById(R.id.editor);
		drawer = findViewById(R.id.mDrawerLayout);

		var toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setHomeButtonEnabled(false);

		var toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.open_drawer, R.string.close_drawer);
		drawer.addDrawerListener(toggle);
		toggle.syncState();

		editor.setTypefaceText(Typeface.MONOSPACE);
		editor.setColorScheme(getColorScheme());
		editor.setEditorLanguage(getTextMateLanguage());
		editor.setTextSize(12);
		editor.setPinLineNumber(true);

		try {
			indexer = new Indexer("editor");
			if (indexer.notHas("currentFile")) {
				indexer.put("currentFile", FileUtil.getJavaDir() + "Main.java");
				indexer.flush();
			}

			currentWorkingFilePath = indexer.getString("currentFile");
		} catch (JSONException e) {
			dialog("JsonException", e.getMessage(), true);
		}

		final var file = file(currentWorkingFilePath);

		if (file.exists()) {
			try {
				editor.setText(FileUtil.readFile(file));
			} catch (IOException e) {
				dialog("Cannot read file", getString(e), true);
			}
		} else {
			try {
				file.getParentFile().mkdirs();
				FileUtil.writeFile(file.getAbsolutePath(), TreeCreateNewFileContent.BUILD_NEW_FILE_CONTENT("Main"));
				editor.setText(TreeCreateNewFileContent.BUILD_NEW_FILE_CONTENT("Main"));
			} catch (IOException e) {
				dialog("Cannot create file", getString(e), true);
			}
		}

		builder = new JavaBuilder(getApplicationContext(), getClassLoader());

		if (!new File(FileUtil.getClasspathDir(), "android.jar").exists()) {
			ZipUtil.unzipFromAssets(MainActivity.this, "android.jar.zip", FileUtil.getClasspathDir());
		}
		var output = new File(FileUtil.getClasspathDir() + "/core-lambda-stubs.jar");
		if (!output.exists()) {
			try {
				FileUtil.writeFile(getAssets().open("core-lambda-stubs.jar"), output.getAbsolutePath());
			} catch (Exception e) {
				showErr(getString(e));
			}
		}

		/* Create Loading Dialog */
		buildLoadingDialog();

		/* Insert Fragment with TreeView into Drawer */
		fragmentTransaction = getSupportFragmentManager().beginTransaction().setReorderingAllowed(true);
		reloadTreeView();

		findViewById(R.id.btn_disassemble).setOnClickListener(v -> disassemble());
		findViewById(R.id.btn_smali2java).setOnClickListener(v -> decompile());
		findViewById(R.id.btn_smali).setOnClickListener(v -> smali());
	}

	void reloadTreeView() {
		fragmentTransaction.replace(R.id.frameLayout, new TreeViewDrawer()).commit();
	}

	/* Build Loading Dialog - This dialog shows on code compilation */
	void buildLoadingDialog() {
		var builder = new MaterialAlertDialogBuilder(MainActivity.this);
		ViewGroup viewGroup = findViewById(android.R.id.content);
		View dialogView = getLayoutInflater().inflate(R.layout.compile_loading_dialog, viewGroup, false);
		builder.setView(dialogView);
		loadingDialog = builder.create();
		loadingDialog.setCancelable(false);
		loadingDialog.setCanceledOnTouchOutside(false);
	}

	/* To Change visible to user Stage TextView Text to actually compiling stage in Compile.java */
	void changeLoadingDialogBuildStage(String stage) {
		if (loadingDialog.isShowing()) {
			/* So, this method is also triggered from another thread (Compile.java)
			 * We need to make sure that this code is executed on main thread */
			runOnUiThread(() -> {
				TextView stage_txt = loadingDialog.findViewById(R.id.stage_txt);
				stage_txt.setText(stage);
			});
		}
	}

	/* Loads a file from a path to the editor */
	public void loadFileToEditor(String path) throws IOException, JSONException {
		var newWorkingFile = new File(path);
		editor.setText(FileUtil.readFile(newWorkingFile));
		indexer.put("currentFile", path);
		indexer.flush();
		currentWorkingFilePath = path;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main_activity_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.format_menu_button) {
			ConcurrentUtil.execute(() -> {
				if (prefs.getString("formatter", "Google Java Formatter").equals("Google Java Formatter")) {
					var formatter = new GoogleJavaFormatter(editor.getText().toString());
					temp = formatter.format();
				} else {
					var formatter = new EclipseJavaFormatter(editor.getText().toString());
					temp = formatter.format();
				}
			});
			editor.setText(temp);
		} else if (id == R.id.settings_menu_button) {

			var intent = new Intent(MainActivity.this, SettingActivity.class);
			startActivity(intent);

		} else if (id == R.id.run_menu_button) {

			compile(true);
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onResume() {
		super.onResume();
		reloadTreeView();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		editor.release();
		if (runThread != null && runThread.isAlive()) {
			runThread.interrupt();
		}
	}

	/* Shows a snackbar indicating that there were problems during compilation */
	public void showErr(final String e) {
		Snackbar.make((LinearLayout) findViewById(R.id.container), "An error occurred", Snackbar.LENGTH_INDEFINITE)
				.setAction("Show error", v -> dialog("Failed...", e, true))
				.show();
	}

	public void compile(boolean execute) {
		final int id = 1;
		var intent = new Intent(MainActivity.this, MainActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		var pendingIntent = PendingIntent.getActivity(MainActivity.this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

		var channel = new NotificationChannel(BUILD_STATUS, "Build Status", NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription("Shows the current build status.");

		final var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		manager.createNotificationChannel(channel);

		final var mBuilder = new Notification.Builder(MainActivity.this, BUILD_STATUS)
				.setContentTitle("Build Status")
				.setSmallIcon(R.mipmap.ic_launcher)
				.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
				.setAutoCancel(true)
				.setContentIntent(pendingIntent);

		loadingDialog.show(); // Show Loading Dialog
		runThread = new Thread(new CompileTask(MainActivity.this, execute, new CompileTask.CompilerListeners() {
			@Override
			public void onCurrentBuildStageChanged(String stage) {
				mBuilder.setContentText(stage);
				manager.notify(id, mBuilder.build());
				changeLoadingDialogBuildStage(stage);
			}

			@Override
			public void onSuccess() {
				loadingDialog.dismiss();
				manager.cancel(id);
			}

			@Override
			public void onFailed(String errorMessage) {
				mBuilder.setContentText("Failure");
				manager.notify(id, mBuilder.build());
				if (loadingDialog.isShowing()) {
					loadingDialog.dismiss();
				}
				showErr(errorMessage);
			}
		}));
		runThread.start();
	}

	private TextMateColorScheme getColorScheme() {
		return new TextMateColorScheme(getDarculaTheme());
	}

	private IRawTheme getDarculaTheme() {
		try {
			var rawTheme = ThemeReader.readThemeSync("darcula.json", getAssets().open("textmate/darcula.json"));
			return rawTheme;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private TextMateLanguage getTextMateLanguage() {
		try {
			var language = TextMateLanguage.create("java.tmLanguage.json",
					getAssets().open("textmate/java/syntaxes/java.tmLanguage.json"),
					new InputStreamReader(getAssets().open("textmate/java/language-configuration.json")),
					getDarculaTheme());
			return language;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void smali() {
		try {
			var classes = getClassesFromDex();
			if (classes == null)
				return;
			listDialog("Select a class to extract source", classes, (d, pos) -> {
				var claz = classes[pos];
				var args = new String[] { "-f", "-o", FileUtil.getBinDir().concat("smali/"),
						FileUtil.getBinDir().concat("classes.dex") };
				ConcurrentUtil.execute(() -> BaksmaliCmd.main(args));

				var edi = new CodeEditor(MainActivity.this);
				edi.setTypefaceText(Typeface.MONOSPACE);
				edi.setColorScheme(getColorScheme());
				edi.setEditorLanguage(getTextMateLanguage());
				edi.setTextSize(13);

				var smaliFile = file(FileUtil.getBinDir() + "smali/" + claz.replace(".", "/") + ".smali");

				try {
					edi.setText(formatSmali(FileUtil.readFile(smaliFile)));
				} catch (IOException e) {
					dialog("Cannot read file", getString(e), true);
				}

				var dialog = new AlertDialog.Builder(MainActivity.this).setView(edi).create();
				dialog.setCanceledOnTouchOutside(true);
				dialog.show();
			});
		} catch (Throwable e) {
			dialog("Failed to extract smali source", getString(e), true);
		}
	}

	public void decompile() {
		final var classes = getClassesFromDex();
		if (classes == null)
			return;
		listDialog("Select a class to extract source", classes, (dialog, pos) -> {
			var claz = classes[pos].replace(".", "/");
			var args = new String[] { FileUtil.getBinDir() + "classes/" + claz + // full class name
					".class", "--extraclasspath", FileUtil.getClasspathDir() + "android.jar", "--outputdir",
					FileUtil.getBinDir() + "cfr/" };

			ConcurrentUtil.execute(() -> {
				try {
					Main.main(args);
				} catch (Exception e) {
					dialog("Failed to decompile...", getString(e), true);
				}
			});

			var edi = new CodeEditor(MainActivity.this);
			edi.setTypefaceText(Typeface.MONOSPACE);
			edi.setColorScheme(getColorScheme());
			edi.setEditorLanguage(getTextMateLanguage());
			edi.setTextSize(12);

			var decompiledFile = file(FileUtil.getBinDir() + "cfr/" + claz + ".java");

			try {
				edi.setText(FileUtil.readFile(decompiledFile));
			} catch (IOException e) {
				dialog("Cannot read file", getString(e), true);
			}

			var d = new AlertDialog.Builder(MainActivity.this).setView(edi).create();
			d.setCanceledOnTouchOutside(true);
			d.show();
		});
	}

	public void disassemble() {
		final var classes = getClassesFromDex();
		if (classes == null)
			return;
		listDialog("Select a class to disassemble", classes, (dialog, pos) -> {
			var claz = classes[pos].replace(".", "/");

			var edi = new CodeEditor(MainActivity.this);
			edi.setTypefaceText(Typeface.MONOSPACE);
			edi.setColorScheme(getColorScheme());
			edi.setEditorLanguage(getTextMateLanguage());
			edi.setTextSize(12);

			try {
				var disassembled = "";
				if (prefs.getString("disassembler", "Javap").equals("Javap")) {
					disassembled = new JavapDisassembler(FileUtil.getBinDir() + "classes/" + claz + ".class")
							.disassemble();
				} else {
					disassembled = new EclipseDisassembler(FileUtil.getBinDir() + "classes/" + claz + ".class")
							.disassemble();
				}
				edi.setText(disassembled);

			} catch (Throwable e) {
				dialog("Failed to disassemble", getString(e), true);
			}
			var d = new AlertDialog.Builder(MainActivity.this).setView(edi).create();
			d.setCanceledOnTouchOutside(true);
			d.show();
		});
	}

	/* Formats a given smali code */
	private String formatSmali(String in) {

		var lines = new ArrayList<String>(Arrays.asList(in.split("\n")));

		var insideMethod = false;

		for (var i = 0; i < lines.size(); i++) {

			var line = lines.get(i);

			if (line.startsWith(".method"))
				insideMethod = true;

			if (line.startsWith(".end method"))
				insideMethod = false;

			if (insideMethod && !shouldSkip(line))
				lines.set(i, line + "\n");
		}

		var result = new StringBuilder();

		for (var i = 0; i < lines.size(); i++) {
			if (i != 0)
				result.append("\n");

			result.append(lines.get(i));
		}

		return result.toString();
	}

	private boolean shouldSkip(String smaliLine) {

		var ops = new String[] { ".line", ":", ".prologue" };

		for (var op : ops) {
			if (smaliLine.trim().startsWith(op))
				return true;
		}
		return false;
	}

	public void listDialog(String title, String[] items, DialogInterface.OnClickListener listener) {
		runOnUiThread(() -> {
			new MaterialAlertDialogBuilder(MainActivity.this).setTitle(title).setItems(items, listener).create().show();
		});
	}

	public void dialog(String title, final String message, boolean copyButton) {
		var dialog = new MaterialAlertDialogBuilder(MainActivity.this).setTitle(title).setMessage(message)
				.setPositiveButton("GOT IT", null).setNegativeButton("CANCEL", null);
		if (copyButton)
			dialog.setNeutralButton("COPY", (dialogInterface, i) -> {
				((ClipboardManager) getSystemService(getApplicationContext().CLIPBOARD_SERVICE))
						.setPrimaryClip(ClipData.newPlainText("clipboard", message));
			});
		dialog.create().show();
	}

	/* Used to find all the compiled classes from the output dex file */
	public String[] getClassesFromDex() {
		try {
			var dex = new File(FileUtil.getBinDir().concat("classes.dex"));
			/* If the project doesn't seem to have been compiled yet, compile it */
			if (!dex.exists()) {
				compile(false);
			}
			var classes = new ArrayList<String>();
			var dexfile = DexFileFactory.loadDexFile(dex.getAbsolutePath(), Opcodes.forApi(26));
			for (var f : dexfile.getClasses().toArray(new ClassDef[0])) {
				var name = f.getType().replace("/", "."); // convert class name to standard form
				classes.add(name.substring(1, name.length() - 1));
			}
			return classes.toArray(new String[0]);
		} catch (Exception e) {
			dialog("Failed to get available classes in dex...", getString(e), true);
			return null;
		}
	}

	public File file(final String path) {
		return new File(path);
	}

	private String getString(final Throwable e) {
		return Log.getStackTraceString(e);
	}
}
