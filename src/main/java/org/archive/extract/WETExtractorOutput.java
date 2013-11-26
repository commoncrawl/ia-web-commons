package org.archive.extract;

import org.archive.format.gzip.GZIPMemberWriter;
import org.archive.format.gzip.GZIPMemberWriterCommittedOutputStream;
import org.archive.format.http.HttpHeaders;
import org.archive.format.json.JSONUtils;
import org.archive.format.warc.WARCRecordWriter;
import org.archive.resource.MetaData;
import org.archive.resource.Resource;
import org.archive.util.DateUtils;
import org.archive.util.IAUtils;
import org.archive.util.StreamCopy;
import org.archive.util.io.CommitedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Date;

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
  private final static Charset UTF8 = Charset.forName("UTF-8");
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


  private String extractOrIO(MetaData md, String path) throws IOException {
    String value = JSONUtils.extractSingle(md, path);
    if(value == null) {
      throw new IOException("No "+path+" found.");
    }
    return value;
  }

  public void output(Resource resource) throws IOException {
    StreamCopy.readToEOF(resource.getInputStream());
    MetaData top = resource.getMetaData().getTopMetaData();
    CommitedOutputStream cos;

    if(!wroteFirst) {
      cos = getOutput();
      writeWARCInfo(cos, top);
      cos.commit();
      wroteFirst = true;
    }
    String envelopeFormat = JSONUtils.extractSingle(top, "Envelope.Format");
    if(envelopeFormat == null) {
      throw new IOException("Missing Envelope.Format");
    }

    String warctype = JSONUtils.extractSingle(top, "Envelope.WARC-Header-Metadata.WARC-Type");
    if (warctype != null && warctype.equals("response")) {
      String textExtract = JSONUtils.extractSingle(top, "Envelope.Payload-Metadata.HTTP-Response-Metadata.HTML-Metadata.Text");

      if (textExtract != null) {
        cos = getOutput();
        if(envelopeFormat.equals("WARC")) {
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

      if(filename == null) {
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
    String targetURI = extractOrIO(md, "Envelope.WARC-Header-Metadata.WARC-Target-URI");

    String capDateString = extractOrIO(md, "Envelope.WARC-Header-Metadata.WARC-Date");
    capDateString = transformWARCDate(capDateString);
    String recId = extractOrIO(md, "Envelope.WARC-Header-Metadata.WARC-Record-ID");
    writeWARCMDRecord(recOut, targetURI, capDateString, recId, textExtract);
  }

  private void writeWARCMDRecord(OutputStream recOut, String targetURI, String capDateString, String recId,
                                 String textExtract)
      throws IOException {

    Date capDate;
    try {
      capDate = DateUtils.getSecondsSinceEpoch(capDateString);

    } catch (ParseException e) {
      e.printStackTrace();
      // TODO... not the write thing...
      capDate = new Date();
    }

    recW.writeTextConversionRecord(recOut, textExtract.getBytes("UTF-8"), targetURI, capDate, recId);
  }

  private static String transformWARCDate(final String input) {

    StringBuilder output = new StringBuilder(14);

    output.append(input.substring(0,4));
    output.append(input.substring(5,7));
    output.append(input.substring(8,10));
    output.append(input.substring(11,13));
    output.append(input.substring(14,16));
    output.append(input.substring(17,19));

    return output.toString();
  }
}
