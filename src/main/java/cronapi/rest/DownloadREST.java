package cronapi.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import cronapi.ErrorResponse;
import cronapi.util.DataType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cronapi.util.LRUCache;

@RestController
@RequestMapping(value = "/api/cronapi")
public class DownloadREST {

  private static int INTERVAL = 1000 * 60 * 10;
  private static LRUCache<String, File> FILES = new LRUCache<>(1000, INTERVAL);
  private static boolean isDebug = ManagementFactory.getRuntimeMXBean().getInputArguments().toString()
          .indexOf("-agentlib:jdwp") > 0;
  public static SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US);
  public static File TEMP_FOLDER;

  private static ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);

  static {
    TEMP_FOLDER =  new File(System.getProperty("java.io.tmpdir"), "CRONAPI_RECYCLE_FILES");
    TEMP_FOLDER.mkdirs();

    executor.scheduleAtFixedRate(() -> {
      File[] files = TEMP_FOLDER.listFiles();
      for (File file: files) {
        try {
          BasicFileAttributes attr = Files.readAttributes(file.toPath(), BasicFileAttributes.class);

          long millis = System.currentTimeMillis() - attr.creationTime().toMillis();

          if (millis > INTERVAL) {
            synchronized (file.getAbsolutePath().intern()) {
              file.delete();
            }
          }

        } catch (IOException e) {
          e.printStackTrace();
        }

      }
    }, 0, Math.round((double)INTERVAL / 2.0), TimeUnit.MILLISECONDS);
  }

  public static File getTempFile(String name) {
    return new File(TEMP_FOLDER, name);
  }
  
  public static String getDownloadUrl(File file) {
    String id = UUID.randomUUID().toString();
    FILES.put(id, file);
    
    return "api/cronapi/download/" + id;
  }
  
  @ExceptionHandler(Throwable.class)
  @ResponseBody
  ResponseEntity<ErrorResponse> handleControllerException(HttpServletRequest req, Throwable ex) {
    ex.printStackTrace();
    ErrorResponse errorResponse = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex, req.getMethod());
    return new ResponseEntity<ErrorResponse>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
  }
  
  @RequestMapping(method = RequestMethod.GET, value = "/download/{id}")
  public void listBlockly(HttpServletRequest request, HttpServletResponse response, @PathVariable("id") String id)
          throws Exception {
    File resourceFile = FILES.get(id);
    
    if(resourceFile == null || !resourceFile.exists()) {
      throw new Exception("File not found!");
    }
    else {

      synchronized (resourceFile.getAbsolutePath().intern()) {
        response.setContentType(DataType.getContentType(resourceFile));
        response.setContentLength((int) resourceFile.length());

        if (request.getParameter("cache") != null && request.getParameter("cache").equalsIgnoreCase("false")) {
          response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
          response.setHeader("Pragma", "no-cache");
          response.setDateHeader("Expires", 0);
        } else {
          response.addHeader("Last-Modified", (format.format(new Date(resourceFile.lastModified()))));
          response.addHeader("ETag", String.valueOf(Math.abs(resourceFile.hashCode())));
        }
        response.addHeader("Connection", "Keep-Alive");
        response.addHeader("Proxy-Connection", "Keep-Alive");

        if (request.getParameter("download") == null || request.getParameter("download").isEmpty() ||
            request.getParameter("download").equalsIgnoreCase("true")) {
          response.setHeader("Content-Disposition", "attachment; filename=\"" + resourceFile.getName() + "\"");
        }

        InputStream in = null;
        ServletOutputStream outs = null;

        byte[] read = new byte[1024];
        int total = 0;
        try {
          in = new FileInputStream(resourceFile);
          outs = response.getOutputStream();

          while ((total = in.read(read)) >= 0) {
            outs.write(read, 0, total);
          }
          outs.flush();
        } catch (IOException ioe) {
          // Abafa
        } finally {
          if (in != null) {
            try {
              in.close();
            } catch (IOException e) {
              // abafa
            }
          }
        }
      }
    }
  }
  
}