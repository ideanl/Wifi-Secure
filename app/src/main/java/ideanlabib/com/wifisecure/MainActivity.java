package ideanlabib.com.wifisecure;

import android.accounts.AccountManager;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

import ideanlabib.com.wifisecure.openvpn.DisconnectVPN;
import ideanlabib.com.wifisecure.openvpn.OpenVPNService;
import ideanlabib.com.wifisecure.openvpn.ProfileManager;
import ideanlabib.com.wifisecure.openvpn.VPNLaunchHelper;
import ideanlabib.com.wifisecure.openvpn.VpnProfile;
import ideanlabib.com.wifisecure.openvpn.VpnStatus;

public class MainActivity extends AppCompatActivity {
    private Button secureButton;
    ProgressDialog progress;

    protected OpenVPNService mService;

    View.OnClickListener secure = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            secure();
        }
    };

    View.OnClickListener unsecure = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            unsecure();
        }
    };

    private VpnProfile profile;

    private final int   LISTEN_PORT = 11445,
                        TUNNEL_PORT = 443;
    private final String TUNNEL_HOST = "ideanvpn.tk";

    private int START_VPN_DIALOG = 1;
    private final int REQUEST_CODE_EMAIL = 2;

    private Process stunnelProcess;
    private String ipAddress = "";

    private Connector server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        secureButton = (Button) findViewById(R.id.secure);
        progress = new ProgressDialog(this);

        profile = getProfileManager().getProfileByName("IdeanVPN");

        getAccount();

        Intent intent = getIntent();
        if (intent != null && intent.getAction() == OpenVPNService.DISCONNECT_VPN) {
            Intent bindIntent = new Intent(this, OpenVPNService.class);
            bindIntent.setAction(OpenVPNService.START_SERVICE);
            boolean result = bindService(bindIntent, connection, BIND_AUTO_CREATE);
            if (!result)
                throw new RuntimeException("Could not rebind to disconnect.");
            unsecure();
        }
    }

    public void secure(View v) {
        secure();
    }

    public void secure() {
        secureButton.setEnabled(false);
        progress.setTitle("Securing");
        progress.setMessage("Please wait...");
        progress.show();
        startStunnel();
        startVPN();
    }

    public void unsecure() {
        secureButton.setEnabled(false);
        progress.setTitle("Closing.");
        progress.setMessage("Please wait...");
        progress.show();
        try {
            stopVPN();
        } catch (Exception e) {
            Log.d("Stuff", "Interrupt failure: " + e.toString());
        }
        Log.d("Connector", "Stopping tunnel " + LISTEN_PORT + ":" + TUNNEL_HOST + ":" + TUNNEL_PORT);

        update(false);
    }

    public void update(boolean secured) {
        secureButton.setEnabled(true);
        progress.dismiss();
        if (secured) {
            secureButton.setText("Unsecure Me");

            secureButton.setOnClickListener(unsecure);
        } else {
            secureButton.setText("Secure Me!");

            secureButton.setOnClickListener(secure);
        }
    }


    private void startVPN() {
        Intent intent = VpnService.prepare(this);
        if (intent != null)
            startActivityForResult(intent, START_VPN_DIALOG);
        else
            onActivityResult(START_VPN_DIALOG, RESULT_OK, intent);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (VpnStatus.mLaststate.equals("NOPROCESS"))
                        Thread.sleep(500);

                    while (!VpnStatus.mLaststate.equals("CONNECTED") && !VpnStatus.mLaststate.equals("NOPROCESS")) {
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (VpnStatus.mLaststate.equals("NOPROCESS")) {
                            progress.dismiss();
                            secureButton.setEnabled(true);
                            stopService(new Intent(mService, OpenVPNService.class));
                            Toast toast = Toast.makeText(getApplicationContext(), "Could not secure connection. Are you connected to the internet?", Toast.LENGTH_LONG);
                            toast.show();
                        } else {
                            update(true);
                        }
                    }
                });
            }
        }).start();
    }

    private void stopVPN() {
        try {
            mService.getManagement().stopVPN();
            Intent intent = new Intent(this, DisconnectVPN.class);
            startActivityForResult(intent, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == START_VPN_DIALOG) {
            if (resultCode == RESULT_OK) {
                try {
                    FileWriter cfg = new FileWriter(VPNLaunchHelper.getConfigFilePath(this));
                    cfg.write(profile.getConfigFile(this, false));
                    cfg.flush();
                    cfg.close();
                } catch (IOException e) {
                    VpnStatus.logException(e);
                }
                Log.d("Connector", "SUCCESS!");
                Intent intent = new Intent(this, OpenVPNService.class);
                startService(intent);

                intent = new Intent(this, OpenVPNService.class);
                intent.setAction(OpenVPNService.START_SERVICE);

                boolean result = bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!result)
                    throw new RuntimeException("Unable to bind with service.");
            } else {
                Log.d("Connector", "FAILURE!");
            }
        } else if (requestCode == REQUEST_CODE_EMAIL && resultCode == RESULT_OK) {
            String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            Log.d("STUFF", accountName);
            initializeApp();
        }
    }

    private void getAccount() {
        Intent intent = AccountPicker.newChooseAccountIntent(null, null,
                new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE}, false, null, null, null, null);
        startActivityForResult(intent, REQUEST_CODE_EMAIL);
    }

    private void initializeApp() {
        progress.setTitle("Initializing");
        progress.setMessage("Please wait...");
        progress.show();

        final Context context = this;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!(new File(getFilesDir().getAbsolutePath() + "/stunnel").exists())) {
                        File stunnel = downloadFile(getApplicationContext(), "http://ideanlabib.tk/files/openvpn/stunnel/stunnel", "stunnel");
                        if (!stunnel.setExecutable(true))
                            throw new RuntimeException("Could not set stunnel to executable");
                    }

                    downloadFile(getApplicationContext(), "http://ideanlabib.tk/files/openvpn/stunnel/stunnel.conf", "stunnel-template.conf");

                    if (profile == null) {
                        Log.d("Connector", "CREATING PROFILE....");
                        //String client = getPhoneNumber();
                        String client = "idean";
                        if (!(new File(getFilesDir().getAbsolutePath() + "/ca.crt").exists())) {
                            downloadFile(context, "http://ideanlabib.tk/files/openvpn/ca.crt", "ca.crt");
                        }
                        String caFileContents = readFile("ca.crt");

                        String keyname = client + ".key";
                        if (!(new File(getFilesDir().getAbsolutePath() + "/" + keyname).exists())) {
                            downloadFile(context, "http://ideanlabib.tk/files/openvpn/" + keyname, keyname);
                        }
                        String clientKeyFileContents = readFile(keyname);

                        String certname = client + ".crt";
                        if (!(new File(getFilesDir().getAbsolutePath() + "/" + certname).exists())) {
                            downloadFile(context, "http://ideanlabib.tk/files/openvpn/" + certname, certname);
                        }
                        String clientCertFileContents = readFile(certname);

                        ProfileManager pm = getProfileManager();
                        profile = new VpnProfile("IdeanVPN");
                        profile.mUseLzo = true;
                        //Certificate stuff
                        //profile.mServerName = "67.183.127.61";
                        profile.mServerName = "127.0.0.1";
                        profile.mServerPort = "1144";

                        profile.mUseUdp = false;
                        profile.mConnectRetryMax = "5";
                        profile.mConnectRetry = "5";
                        profile.mUseDefaultRoute = true;
                        profile.mUseDefaultRoutev6 = true;
                        profile.mUsePull = true;
                        profile.mPersistTun = true;

                        profile.mConnections[0].mEnabled = true;
                        profile.mConnections[0].mServerName = "127.0.0.1";
                        profile.mConnections[0].mServerPort = "1144";
                        profile.mConnections[0].mUseUdp = false;

                        profile.mCaFilename = "[[NAME]]ca.crt[[INLINE]]" + caFileContents;
                        profile.mClientKeyFilename = "[[NAME]]" + client + ".key[[INLINE]]" + clientKeyFileContents;
                        profile.mClientCertFilename = "[[NAME]]" + client + ".cert[[INLINE]]" + clientCertFileContents;
                        pm.addProfile(profile);
                        pm.saveProfileList(context);
                        pm.saveProfile(context, profile);
                    }
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            secureButton.setEnabled(true);
                            progress.dismiss();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    private ProfileManager getProfileManager() {
        return ProfileManager.getInstance(this);
    }

    private String getPhoneNumber() {
        TelephonyManager tMgr = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        return tMgr.getLine1Number();
    }

    private String readFile(String name) {
        try {
            FileInputStream fis = openFileInput(name);
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[10240];
            try {
                for (int length = 0; (length = fis.read(buffer)) > 0;) {
                    out.append(new String(buffer, 0, length));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            fis.close();

            return out.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private File downloadFile(final Context context, final String url, final String filename) {
        try {
            URL website = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) website.openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.d("Connector", "Server returned HTTP " + connection.getResponseCode()
                        + " " + connection.getResponseMessage());
            }

            InputStream input = connection.getInputStream();
            FileOutputStream output = context.openFileOutput(filename, Context.MODE_PRIVATE);

            byte data[] = new byte[4096];
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }
            output.close();

            return new File(getFilesDir().getAbsolutePath() + "/" + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unbindService(connection);
    }

    private void startStunnel() {
        try {
            makeStunnelConf();

            String path = getFilesDir().getAbsolutePath() + "/";
            String[] command = {path + "stunnel", path + "stunnel.conf"};
            ProcessBuilder p = new ProcessBuilder(command);
            stunnelProcess = p.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void makeStunnelConf() {
        String content = readFile("stunnel-template.conf");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized(ipAddress) {
                        InetAddress address = InetAddress.getByName(new URL("http://ideanvpn.tk").getHost());
                        ipAddress.notify();
                        ipAddress = address.getHostAddress();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException("FAILED TO GET IP");
                }
            }
        }).start();

        synchronized(ipAddress) {
            try {
                ipAddress.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException("Darn. Got interrupted");
            }

            content = content.replaceAll("IP_GOES_HERE", ipAddress);

            try {
                FileOutputStream output = this.openFileOutput("stunnel.conf", Context.MODE_PRIVATE);
                output.write(content.getBytes());
                output.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            OpenVPNService.LocalBinder binder = (OpenVPNService.LocalBinder) service;
            mService = binder.getService();
            mService.stunnelProcess = stunnelProcess;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d("Connector", "DISCONNECTED!");
            mService = null;
        }
    };
}