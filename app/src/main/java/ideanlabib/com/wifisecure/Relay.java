package ideanlabib.com.wifisecure;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

/**
 * Created by idean on 2/6/16.
 */
public class Relay extends Thread {
    private final Connector connector;
    private InputStream in;
    private OutputStream out;
    private String side;
    private int sessionid;
    private final static int BUFSIZ = 4096;
    private byte buf[] = new byte[BUFSIZ];

    public Relay(Connector connector, InputStream in, OutputStream out, String side, int sessionid) {
        this.connector = connector;
        this.in = in;
        this.out = out;
        this.side = side;
        this.sessionid = sessionid;
    }

    public void run() {
        int n = 0;

        try {
            while ((n = in.read(buf)) > 0) {
                if (Thread.interrupted()) {
                    // We've been interrupted: no more relaying
                    Log.d("Connector", "Interrupted " + side + " thread");
                    try {
                        in.close();
                        out.close();
                    } catch (IOException e) {
                        Log.d("Connector", e.toString());
                    }
                    return;
                }
                out.write(buf, 0, n);
                out.flush();

                for (int i = 0; i < n; i++) {
                    if (buf[i] == 7)
                        buf[i] = '#';
                }
            }
        } catch (SocketException e) {
            Log.d("Connector", e.toString());
        } catch (IOException e) {
            Log.d("Connector", e.toString());
        } finally {
            try {
                in.close();
                out.close();
            } catch (IOException e) {
                Log.d("Connector", e.toString());
            }
        }
        Log.d("Connector", "Quitting "+side+"-side stream proxy...");
    }
}
