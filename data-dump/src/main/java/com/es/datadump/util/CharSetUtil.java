package com.es.datadump.util;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * @author yiheni
 */
public class CharSetUtil {
    public static String bytesToString(byte[] input, String sourceCharSet, String destCharSet) throws CharacterCodingException {
        if (ArrayUtils.isEmpty(input)) {
            return StringUtils.EMPTY;
        }

        ByteBuffer buffer = ByteBuffer.allocate(input.length);

        buffer.put(input);
        buffer.flip();

        //Charset defCharSet;
        //CharsetDecoder defCharSetDecoder;
        //CharBuffer defCharSetCharBuffer;

        //Charset srcCharSet;
        //ByteBuffer srcByteBuffer;

       // Charset desCharSet;
        //CharsetDecoder desDecoder;
       // CharBuffer desCharBuffer;

        Charset defCharSet = Charset.defaultCharset();
        CharsetDecoder defCharSetDecoder = defCharSet.newDecoder();
        CharBuffer defCharSetCharBuffer = defCharSetDecoder.decode(buffer.asReadOnlyBuffer());

        Charset srcCharSet = Charset.forName(sourceCharSet);
        ByteBuffer srcByteBuffer = srcCharSet.encode(defCharSetCharBuffer);


        Charset desCharSet = Charset.forName(destCharSet);
        CharsetDecoder desDecoder = desCharSet.newDecoder();

        CharBuffer desCharBuffer = desDecoder.decode(srcByteBuffer);

        return desCharBuffer.toString();
    }
}
