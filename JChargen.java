import java.io.*;
import java.net.*;
import java.util.*;

// misc

class Tools {
  private final static int BLOBSIZE = 1024 * 1024;

  static void debug(long id, String s, int i) {
    debug(id, s, (long)i);
  }

  static void debug(long id, String s, long l) {
    String msg = String.format("[%d] %s: %d", id, s, l);
    System.out.println(msg);
  }

  static void debug(long id, String s, String t) {
    String msg = String.format("[%d] %s: %s", id, s, t);
    System.out.println(msg);
  }

  static void debug(long id, Exception e) {
    String msg = String.format("[%d] exception: %s",
                               id,
                               e.getMessage());
    System.out.println(msg);
  }

  static byte[] blob(String hostname, int daemon) {
    StringBuffer sb = new StringBuffer();
    for (int n = 1; sb.length() < BLOBSIZE; n++) {
      String s =
        String
        .format("---- HELLO! YOU ARE CONNECTED TO %s DAEMON %d ---- [%d]\n",
                hostname,
                daemon,
                n);
      sb.append(s);
    }
    return sb.toString().getBytes();
  }

  static byte[] banner(String hostname, int daemon) {
    StringBuffer sb = new StringBuffer();
    sb.append("HTTP/1.0 200 OK\r\n");
    sb.append("Content-type: text/plain\r\n");
    sb.append("\r\n");
    String s =
      String
      .format("---- HELLO! YOU ARE CONNECTED TO %s DAEMON %d ----\r\n",
              hostname,
              daemon);
      sb.append(s);
    return sb.toString().getBytes();
  }
}

// this would be a general SendOnce worker except that browsers love
// sending us HTTP requests, and unless you make a stab at reading
// them, the browser will get upset when it receives a TCP Reset.

class HttpDrain implements Runnable {
  Socket sock;

  HttpDrain(Socket sock) {
    this.sock = sock;
  }

  public void run() {
    try {
      InputStream is = sock.getInputStream();
      byte[] ignored = new byte[1024];
      int nread = is.read(ignored);
      // if you wanna do anything with nread,
      // remember to check for `-1` on EOF
    } catch (Exception e) {
      // e.printStackTrace();
    }
  }
}

class HttpWorker extends Worker {
  HttpWorker(Socket sock, int port, byte[] blob) {
    super(sock, port, blob);
  }

  @Override
  void sendTraffic() throws IOException {
    sock.setSoLinger(false, 0);
    // sock.shutdownInput(); // it turns out this doesn't always DWYW
    try {
      // limited disposal of input to make curl happy
      Thread drain = new Thread(new HttpDrain(sock));
      drain.start();
      // you have 0.1 second to send me up to 1kb of request
      // - which I shall ignore anyway.
      Thread.currentThread().sleep(100);
      sock.getOutputStream().write(blob); // write stuff once
      byteCounter += blob.length;
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}

// sends the traffic once

class SendOnceWorker extends Worker {
  SendOnceWorker(Socket sock, int port, byte[] blob) {
    super(sock, port, blob);
  }

  @Override
  void sendTraffic() throws IOException {
    sock.setSoLinger(false, 0);
    sock.shutdownInput(); // it turns out this doesn't always DWYW
    sock.getOutputStream().write(blob); // write stuff once
    byteCounter += blob.length;
  }
}

// sends the traffic in a loop until throwing occurs

class SendLoopWorker extends Worker {
  SendLoopWorker(Socket sock, int port, byte[] blob) {
    super(sock, port, blob);
  }

  @Override
  void sendTraffic() throws IOException {
    sock.setSoLinger(false, 0);
    sock.shutdownInput(); // no messing around with drains
    OutputStream os = sock.getOutputStream();
    while (true) {
      os.write(blob);
      byteCounter += blob.length; // in java we trust
    }
  }
}

// framework

abstract class Worker implements Runnable {
  Socket sock;
  int port;
  byte[] blob;
  long byteCounter = 0;

  Worker(Socket sock, int port, byte[] blob) {
    this.sock = sock;
    this.port = port;
    this.blob = blob;
  }

  abstract void sendTraffic() throws IOException;

  public void run() {
    long start = System.currentTimeMillis();
    long id = Thread.currentThread().getId();
    Tools.debug(id, "new-worker", port);

    try {
      sendTraffic();
    } catch (Exception e) {
      Tools.debug(id, e);
    } finally {
      try {
        long stop = System.currentTimeMillis();
        long delta = stop - start;
        long divisor = delta / 1000;
        long bps = 0;
        if (divisor >= 10) {
          bps = byteCounter / divisor;
        }
        Tools.debug(id,
                    "wrote",
                    "port=" + port +
                    " count=" + byteCounter +
                    " millis=" + delta +
                    " bps=" + bps
                    );
        sock.close();
      } catch (IOException e2) {
        e2.printStackTrace();
      }
    }
  }
}

// chargen-specific listener

class ChargenListener extends Listener {
  byte[] blob;

  ChargenListener(int port, String hostname, int daemon) {
    super(port, hostname, daemon);
    this.blob = Tools.blob(hostname, daemon);
  }

  @Override
  Worker getWorker(Socket sock) {
    return new SendLoopWorker(sock, port, blob);
  }
}

// http-specific listener

class HttpListener extends Listener {
  byte[] blob;

  HttpListener(int port, String hostname, int daemon) {
    super(port, hostname, daemon);
    this.blob = Tools.banner(hostname, daemon);
  }

  @Override
  Worker getWorker(Socket sock) {
    return new HttpWorker(sock, port, blob);
  }
}

// framework

abstract class Listener implements Runnable {
  int port;
  String hostname;
  int daemon;

  Listener(int port, String hostname, int daemon) {
    this.port = port;
    this.hostname = hostname;
    this.daemon = daemon;
  }

  abstract Worker getWorker(Socket sock);

  public void run() {
    ServerSocket server = null;
    long id = Thread.currentThread().getId();
    Tools.debug(id, "new-listener", port);
    try {
      server = new ServerSocket(port);
      while (true) {
        new Thread(getWorker(server.accept())).start();
      }
    } catch (Exception e) {
      Tools.debug(id, e);
    } finally {
      try {
        server.close();
      } catch (IOException e2) {
        e2.printStackTrace();
      }
    }
  }
}

// driver routine
public class JChargen {
  private static final int CHARGEN_BASE = 8500;
  private static final int HTTP_BASE = 10500;

  public static void main(String[] args) throws Exception {
    // who are we
    String hostname = InetAddress.getLocalHost().getHostName();

    // how many daemons?
    int ndaemons = Integer.valueOf(args[0]);

    // build a stack
    ArrayList<Listener> listeners = new ArrayList<>();
    for(int i = 1; i <= ndaemons; i++) {
      listeners.add(new HttpListener(HTTP_BASE + i, hostname, i));
      listeners.add(new ChargenListener(CHARGEN_BASE + i, hostname, i));
    }

    // make the corresponding threads
    Thread[] threads = new Thread[listeners.size()];
    for(int i = 0; i < threads.length; i++) {
      threads[i] = new Thread(listeners.get(i));
    }

    // launch them
    for(int i = 0; i < threads.length; i++) {
      threads[i].start();
    }

    // wait for them
    for(int i = 0; i < threads.length; i++) {
      threads[i].join();
    }
  }
}
