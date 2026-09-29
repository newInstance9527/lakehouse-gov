package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

class GovLcStorageInventoryCalibratorTest {

    @Test
    void sumCsvWithHeader() throws Exception {
        String csv = "key,size\na,10\nb,20\n";
        long n = GovLcStorageInventoryCalibrator.sumCsvSizes(new BufferedReader(new StringReader(csv)));
        Assertions.assertEquals(30L, n);
    }

    @Test
    void sumCsvHeaderless() throws Exception {
        String csv = "obj1,100\nobj2,50\n";
        long n = GovLcStorageInventoryCalibrator.sumCsvSizes(new BufferedReader(new StringReader(csv)));
        Assertions.assertEquals(150L, n);
    }
}
