package org.apache.iotdb.db.engine.cache;

import org.apache.iotdb.tsfile.file.metadata.TsDeviceMetadata;
import org.apache.iotdb.tsfile.file.metadata.TsFileMetaData;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * This class is used to read metadata(<code>TsFileMetaData</code> and
 * <code>TsRowGroupBlockMetaData</code>).
 * 
 * @author liukun
 *
 */
public class TsFileMetadataUtils {

	private static final Logger LOGGER = LoggerFactory.getLogger(TsFileMetadataUtils.class);

	public static TsFileMetaData getTsFileMetaData(String filePath) throws IOException {
		TsFileSequenceReader reader = null;
		try {
			reader = new TsFileSequenceReader(filePath);
			return reader.readFileMetadata();
		} finally {
			if (reader != null) {
				reader.close();
			}
		}
	}

	public static TsDeviceMetadata getTsRowGroupBlockMetaData(String filePath, String deviceId,
															  TsFileMetaData fileMetaData) throws IOException {
		if (!fileMetaData.getDeviceMap().containsKey(deviceId)) {
			return null;
		} else {
			TsFileSequenceReader reader = null;
			try {
				reader = new TsFileSequenceReader(filePath);
				long offset = fileMetaData.getDeviceMap().get(deviceId).getOffset();
				int size = fileMetaData.getDeviceMap().get(deviceId).getLen();
				ByteBuffer data = ByteBuffer.allocate(size);
				reader.readRaw(offset, size, data);
				data.flip();
				return TsDeviceMetadata.deserializeFrom(data);
			} finally {
				if(reader != null) {
					reader.close();
				}
			}
		}
	}
}