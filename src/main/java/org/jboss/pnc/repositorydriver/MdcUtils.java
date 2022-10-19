package org.jboss.pnc.repositorydriver;

import java.util.HashMap;
import java.util.Map;

import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.slf4j.MDC;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class MdcUtils {

    public static void putMdcToResultMap(
            Map<String, String> result,
            Map<String, String> mdcMap,
            MDCHeaderKeys mdcHeaderKeys) throws RepositoryDriverException {
        if (mdcMap == null) {
            throw new RepositoryDriverException("Missing MDC map.");
        }
        if (mdcMap.get(mdcHeaderKeys.getMdcKey()) != null) {
            result.put(mdcHeaderKeys.getHeaderName(), mdcMap.get(mdcHeaderKeys.getMdcKey()));
        } else {
            throw new RepositoryDriverException("Missing MDC value " + mdcHeaderKeys.getMdcKey());
        }
    }

    public static void silentlyPutMdcToResultMap(
            Map<String, String> result,
            Map<String, String> mdcMap,
            MDCHeaderKeys mdcHeaderKeys) throws RepositoryDriverException {
        if (mdcMap == null) {
            throw new RepositoryDriverException("Missing MDC map.");
        }
        if (mdcMap.get(mdcHeaderKeys.getMdcKey()) != null) {
            result.put(mdcHeaderKeys.getHeaderName(), mdcMap.get(mdcHeaderKeys.getMdcKey()));
        }
    }

    public static Map<String, String> mdcToMapWithHeaderKeys() throws RepositoryDriverException {
        Map<String, String> result = new HashMap<>();
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.PROCESS_CONTEXT);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.TMP);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.EXP);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.USER_ID);
        silentlyPutMdcToResultMap(result, mdcMap, MDCHeaderKeys.TRACE_ID);
        silentlyPutMdcToResultMap(result, mdcMap, MDCHeaderKeys.SPAN_ID);
        silentlyPutMdcToResultMap(result, mdcMap, MDCHeaderKeys.PARENT_ID);
        return result;
    }
}
