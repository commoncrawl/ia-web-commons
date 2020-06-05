package org.archive.extract;

import org.archive.format.gzip.GZIPMemberWriter;
import org.archive.format.gzip.GZIPMemberWriterCommittedOutputStream;
import org.archive.format.http.HttpHeaders;
import org.archive.format.json.JSONUtils;
import org.archive.format.warc.WARCRecordWriter;
import org.archive.resource.MetaData;
import org.archive.resource.Resource;
import org.archive.util.IAUtils;
import org.archive.util.StreamCopy;
import org.archive.util.io.CommitedOutputStream;

import com.github.openjson.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * This is for generating a WARC Encapsulated Text file
 *
 * These are implemented as WARC conversion records. Only
 * Envelope.Payload-Metadata.HTTP-Response-Metadata.HTML-Metadata.Text fields are included
 */
public class WETExtractorOutput implements ExtractorOutput {
  WARCRecordWriter recW;
  private boolean wroteFirst;
  private GZIPMemberWriter gzW;
  private static int DEFAULT_BUFFER_RAM = 1024 * 1024;
  private int bufferRAM = DEFAULT_BUFFER_RAM;
  private String outFilename;

  public WETExtractorOutput(OutputStream out) {
    this(out, null);
  }

  public WETExtractorOutput(OutputStream out, String filename) {
    gzW = new GZIPMemberWriter(out);
    recW = new WARCRecordWriter();
    wroteFirst = false;
    outFilename = filename;
  }

  private CommitedOutputStream getOutput() {
    return new GZIPMemberWriterCommittedOutputStream(gzW,bufferRAM);
  }


  public void output(Resource resource) throws IOException {
    StreamCopy.readToEOF(resource.getInputStream());
    MetaData top = resource.getMetaData().getTopMetaData();
    CommitedOutputStream cos;

    if (!wroteFirst) {
      cos = getOutput();
      writeWARCInfo(cos, top);
      cos.commit();
      wroteFirst = true;
    }
    String envelopeFormat = JSONUtils.extractSingle(top, "Envelope.Format");
    if (envelopeFormat == null) {
      throw new IOException("Missing Envelope.Format");
    }

    String warctype = JSONUtils.extractSingle(top, "Envelope.WARC-Header-Metadata.WARC-Type");
    if (warctype == null)
      return;

    if (warctype.equals("response")) {
      String textExtract = JSONUtils.extractSingle(top, "Envelope.Payload-Metadata.HTTP-Response-Metadata.HTML-Metadata.Text");

      if (textExtract != null) {
        cos = getOutput();
        if (envelopeFormat.startsWith("WARC")) {
          writeWARC(cos, top, textExtract);
        } else {
          // hrm...
          throw new IOException("Unknown Envelope.Format");
        }
        cos.commit();
      }
    }
  }

  private void writeWARCInfo(OutputStream recOut, MetaData md) throws IOException {
    String filename = outFilename;

    if (filename == null) {
      filename = JSONUtils.extractSingle(md, "Container.Filename");

      if (filename == null) {
        throw new IOException("No Container.Filename...");
      }
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("Software-Info", IAUtils.COMMONS_VERSION);
    headers.addDateHeader("Extracted-Date", new Date());

    // Dup out some useful headers from the incoming warcinfo
    String warctype = JSONUtils.extractSingle(md, "Envelope.WARC-Header-Metadata.WARC-Type");
    if (warctype != null && warctype.equals("warcinfo")) {
      final String[] usefulHeaders = {"robots", "isPartOf", "operator", "description", "publisher"};

      for (String header : usefulHeaders) {
        String value = JSONUtils.extractSingle(md, "Envelope.Payload-Metadata.WARC-Info-Metadata." + header);
        if (value != null) {
          headers.add(header, value);
        }
      }
    }


    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    headers.write(baos);
    recW.writeWARCInfoRecord(recOut, filename, baos.toByteArray());
  }

  private void writeWARC(OutputStream recOut, MetaData md, String textExtract) throws IOException {
    JSONObject headers = JSONUtils.extractObject(md, "Envelope.WARC-Header-Metadata");
    String targetURI = headers.getString("WARC-Target-URI");
    String capDateString = headers.getString("WARC-Date");
    String recId = headers.getString("WARC-Record-ID");
    Map<String, String> addHeaders = null;
    if (headers.has("WARC-Identified-Content-Language")) {
      addHeaders = new TreeMap<String, String>();
      addHeaders.put("WARC-Identified-Content-Language", headers.getString("WARC-Identified-Content-Language"));
    }
    writeWARCMDRecord(recOut, targetURI, parseWarcDate(capDateString), recId, textExtract, addHeaders);
  }

  private static Date parseWarcDate(String capDateString) {
	Date capDate;
	ZonedDateTime zdt = ZonedDateTime.from(
			DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of(ZoneOffset.UTC.toString())).parse(capDateString));
	capDate = Date.from(zdt.toInstant());
	return capDate;
  }

  private void writeWARCMDRecord(OutputStream recOut, String targetURI, Date capDate, String recId,
                                 String textExtract, Map<String,String> addHeaders)
      throws IOException {
    recW.writeTextConversionRecord(recOut, textExtract.getBytes(StandardCharsets.UTF_8), targetURI, capDate, recId, addHeaders);
  }

}
