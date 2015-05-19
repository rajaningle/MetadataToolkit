package org.fao.geonet.services.ogp;

import jeeves.server.context.ServiceContext;
import jeeves.services.ReadWriteController;
import org.apache.commons.lang.StringUtils;
import org.fao.geonet.Logger;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by JuanLuis on 15/05/2015.
 */
@Controller("ogp.editController")
@ReadWriteController
@SessionAttributes({"wizardFormBean"})
public class OgpEditController {
    private final Logger logger;
    @Autowired
    private DataManager metadataManager;
    @Autowired
    private SchemaManager schemaManager;
    @Autowired
    private SettingManager settingManager;
    @Autowired
    private OgpDataTypes ogpController;

    public OgpEditController() {
        logger = Log.createLogger("ogp.editController", "ogp");

    }

    @ModelAttribute("wizardFormBean")
    public OgpEditFormBean createWizardFormBean() {
        return new OgpEditFormBean();
    }

    @RequestMapping(value = "{lang}/ogp.edit.clearStatus", produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> resetSessionAttributes(SessionStatus sessionStatus, @PathVariable("lang") String lang) {
        sessionStatus.setComplete();
        return new ResponseEntity<Object>(Boolean.TRUE, HttpStatus.OK);
    }

    @RequestMapping(value = "/{lang}/ogp.edit.addRecord", produces = MediaType.APPLICATION_JSON_VALUE)
    public
    @ResponseBody
    ResponseEntity<Object> addDatasetMetadata(@ModelAttribute("wizardFormBean") OgpEditFormBean wizardFormBean,
                                              @RequestParam("mefFile") MultipartFile file,
                                              @RequestParam(value = "step") String stepString, @PathVariable("lang") String lang) {
        if (logger.isDebugEnabled()) {
            logger.debug("Adding dataset metadata " + wizardFormBean.toString());
            logger.debug("Import step: " + stepString);
        }
        Step step;
        try {
            step = Step.valueOf(stepString);
        } catch (IllegalArgumentException iae) {
            // IAE is thrown when stepString doesn't contain any valid Step item
            return new ResponseEntity<Object>("'step' parameter value is not valid. It must be one of " + Arrays.toString(Step.values()),
                    HttpStatus.BAD_REQUEST);
        }


        if (!file.isEmpty()) {
            try {
                InputStream content = file.getInputStream();
                Element md = Xml.loadStream(content);
                String metadataSchema = metadataManager.autodetectSchema(md, null);
                if (metadataSchema == null) {
                    updateWizardObject(wizardFormBean, null, step);
                    return new ResponseEntity<Object>("Schema not supported", HttpStatus.BAD_REQUEST);
                }

                updateWizardObject(wizardFormBean, md, step);

            } catch (JDOMException jde) {
                updateWizardObject(wizardFormBean, null, step);
                return new ResponseEntity<Object>("Not a valid XML document", HttpStatus.BAD_REQUEST);
            } catch (Exception e) {
                updateWizardObject(wizardFormBean, null, step);
                return new ResponseEntity<Object>("Error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            return new ResponseEntity<Object>("File is empty", HttpStatus.BAD_REQUEST);
        }
        String[] result = {Boolean.TRUE.toString()};
        return new ResponseEntity<Object>(result, HttpStatus.OK);
    }

    @RequestMapping(value = "/{lang}/ogp.edit.importOgpRecord", produces = MediaType.APPLICATION_JSON_VALUE)
    public
    @ResponseBody
    ResponseEntity<Object> addDatasetMetadata(@ModelAttribute("wizardFormBean") OgpEditFormBean wizardFormBean,
                                              @RequestParam(value = "layerId") String layerId, @PathVariable("lang") String lang) {
        try {
            Element ogpMetadata = ogpController.getMetadataAsElement(layerId);
            wizardFormBean.setOgpImportedMetadata(ogpMetadata);
            wizardFormBean.setLocalMetadataRecord(null);
        } catch (IOException e) {
            String result = "There was an error contacting OGP server.";
            wizardFormBean.setOgpImportedMetadata(null);
            return new ResponseEntity<Object>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (JDOMException e) {
            String result = "Error parsing the metadata: " + e.getMessage();
            return new ResponseEntity<Object>(result, HttpStatus.INTERNAL_SERVER_ERROR);

        } catch (Exception e) {
            String result = "Error parsing o transforming the metadata: " + e.getMessage();
            return new ResponseEntity<Object>(result, HttpStatus.INTERNAL_SERVER_ERROR);

        }
        return new ResponseEntity<Object>(Boolean.TRUE.toString(), HttpStatus.OK);
    }

    @RequestMapping(value="/{lang}/ogp.edit.doImport", produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> doImport(@ModelAttribute("wizardFormBean") OgpEditFormBean wizardFormBean,
                                                         SessionStatus status,
                                                         @RequestBody OgpDoEditBean formBean, @PathVariable("lang") String lang) {
        Element template = null;
        Element metadata = null;
        String createdId;

        // Retrieve the template from the database.
        if (StringUtils.isNotBlank(formBean.getTemplateId())) {
            try {
                template = metadataManager.getMetadata(formBean.getTemplateId());
                if (template == null) {
                    String result = "Could not find the template " + formBean.getTemplateId();
                    return createErrorResponse(result, HttpStatus.BAD_REQUEST);
                }
            } catch (Exception e) {
                String result = "Could not retrieve the template " + formBean.getTemplateId();
                return createErrorResponse(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        if (StringUtils.isEmpty(formBean.getGroup())) {
            String result = "Group not valid";
            return createErrorResponse(result, HttpStatus.BAD_REQUEST);
        }
        try {
            Path etlMergeStylesheet = schemaManager.getSchemaDir("iso19115-3").resolve(Geonet.Path.PROCESS_STYLESHEETS)
                    .resolve("etl-merge.xsl");
            Path templateMergeStylesheet = schemaManager.getSchemaDir("iso19115-3").resolve(Geonet.Path.PROCESS_STYLESHEETS)
                    .resolve("template-merge.xsl");
            if (template != null) {
                metadata = template;
                if (formBean.isDatasetImported() && wizardFormBean.getDatasetMetadata() != null) {
                    metadata = Xml.transformWithXmlParam(template, etlMergeStylesheet.toString(), "etlXml", Xml.getString(wizardFormBean.getDatasetMetadata()));
                }
                Element step2Metadata = null;
                if (formBean.isLocalRecordImported() && wizardFormBean.getLocalMetadataRecord() != null) {
                    step2Metadata = wizardFormBean.getLocalMetadataRecord();
                } else if (formBean.isOgpRecordImported() && wizardFormBean.getOgpImportedMetadata() != null) {
                    step2Metadata = wizardFormBean.getOgpImportedMetadata();
                }
                if (step2Metadata != null) {
                    metadata = Xml.transformWithXmlParam(metadata, templateMergeStylesheet.toString(), "templateXml", Xml.getString(step2Metadata));
                }
            } else {
                // not from template
                // Check if we have at least one record to edit
                if (wizardFormBean.getLocalMetadataRecord() == null && wizardFormBean.getOgpImportedMetadata() == null && wizardFormBean.getDatasetMetadata() == null) {
                    String result = "Before you can edit the record you must import a dataset, a local record or a remote one from OGP";
                    return createErrorResponse(result, HttpStatus.BAD_REQUEST);
                }
                if (formBean.isDatasetImported() && wizardFormBean.getDatasetMetadata() != null) {
                    metadata = wizardFormBean.getDatasetMetadata();
                }
                Element step2Metadata = null;
                if (formBean.isLocalRecordImported() && wizardFormBean.getLocalMetadataRecord() != null) {
                    step2Metadata = wizardFormBean.getLocalMetadataRecord();
                } else if (formBean.isOgpRecordImported() && wizardFormBean.getOgpImportedMetadata() != null) {
                    step2Metadata = wizardFormBean.getOgpImportedMetadata();
                }
                if (metadata == null && step2Metadata != null) {
                    metadata = step2Metadata;
                } else if (metadata != null && step2Metadata !=null) {
                    metadata = Xml.transformWithXmlParam(metadata, templateMergeStylesheet.toString(), "templateXml", Xml.getString(step2Metadata));
                }
            }
            if (metadata == null) {
                String result = "Not enough data to perform the operation. Please restart the wizard.";
                return createErrorResponse(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            // Save the record
            // Import record
            String uuid = UUID.randomUUID().toString();
            String date = new ISODate().toString();
            ServiceContext context = ServiceContext.get();
            int userId = context.getUserSession().getUserIdAsInt();
            String docType = null, category = null;
            boolean ufo = false, indexImmediate = true;
            createdId = metadataManager.insertMetadata(context, "iso19115-3" , metadata, uuid,
                    userId, formBean.getGroup(), settingManager.getSiteId(), MetadataType.METADATA.codeString, docType, category, date, date, ufo, indexImmediate);

        } catch (Exception e) {
            String result = "Error creating the record: " + e.getMessage();
            return createErrorResponse(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        status.setComplete();

        return new ResponseEntity<Object>(createdId, HttpStatus.OK);
    }

    /**
     * Create an error response in the form {"error": result}.
     * @param result error text.
     * @param status HTTP server response status.
     * @return a new ResponseEntity.
     */
    private ResponseEntity<Object> createErrorResponse(String result, HttpStatus status) {
        Map<String, String> result2 = new HashMap<>();
        result2.put("error", result);
        return new ResponseEntity<Object>(result2, status);
    }

    private void updateWizardObject(OgpEditFormBean bean, Element metadata, Step step) {
        switch (step) {
            case importDataProperties:
                bean.setDatasetMetadata(metadata);
                break;
            case importXmlMetadata:
                bean.setLocalMetadataRecord(metadata);
                break;
        }
    }

    /**
     * Wizard step
     */
    public enum Step {
        importDataProperties,
        importXmlMetadata

    }

}
