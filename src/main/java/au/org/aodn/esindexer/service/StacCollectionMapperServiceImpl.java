package au.org.aodn.esindexer.service;

import au.org.aodn.esindexer.exception.MappingValueException;
import au.org.aodn.esindexer.model.ContactsModel;
import au.org.aodn.esindexer.model.LanguageModel;
import au.org.aodn.esindexer.model.ThemesModel;
import au.org.aodn.esindexer.utils.BBoxUtils;
import au.org.aodn.esindexer.model.StacCollectionModel;
import au.org.aodn.esindexer.utils.GeometryUtils;
import au.org.aodn.metadata.iso19115_3_2018.*;
import jakarta.xml.bind.JAXBElement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Mapper(componentModel = "spring")
public abstract class StacCollectionMapperServiceImpl implements StacCollectionMapperService {

    @Mapping(target="uuid", source = "source", qualifiedByName = "mapUUID")
    @Mapping(target="title", source = "source", qualifiedByName = "mapTitle" )
    @Mapping(target="description", source = "source", qualifiedByName = "mapDescription")
    @Mapping(target="summaries.score", source = "source", qualifiedByName = "mapSummaries.score")
    @Mapping(target="summaries.status", source = "source", qualifiedByName = "mapSummaries.status")
    @Mapping(target="summaries.scope", source = "source", qualifiedByName = "mapSummaries.scope")
    @Mapping(target="summaries.geometry", source = "source", qualifiedByName = "mapSummaries.geometry")
    @Mapping(target="extent.bbox", source = "source", qualifiedByName = "mapExtentBbox")
    @Mapping(target="contacts", source = "source", qualifiedByName = "mapContacts")
    @Mapping(target="themes", source = "source", qualifiedByName = "mapThemes")
    @Mapping(target="languages", source = "source", qualifiedByName = "mapLanguages")
    public abstract StacCollectionModel mapToSTACCollection(MDMetadataType source);

    private static final Logger logger = LoggerFactory.getLogger(StacCollectionMapperServiceImpl.class);

    @Named("mapUUID")
    String mapUUID(MDMetadataType source) {
        return source.getMetadataIdentifier().getMDIdentifier().getCode().getCharacterString().getValue().toString();
    }

    @Named("mapExtentBbox")
    List<List<BigDecimal>> mapExtentBbox(MDMetadataType source) {
        return createGeometryItems(
                source,
                BBoxUtils::createBBoxFromEXBoundingPolygonType,
                BBoxUtils::createBBoxFromEXGeographicBoundingBoxType
        );
    }
    /**
     * Custom mapping for description field, name convention is start with map then the field name
     * @param source
     * @return
     */
    @Named("mapDescription")
    String mapDescription(MDMetadataType source) {
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);

        if(!items.isEmpty()) {
            // Need to assert only 1 block contains our target
            for(MDDataIdentificationType i : items) {
                // TODO: Null or empty check
                return i.getAbstract().getCharacterString().getValue().toString();
            }
        }
        return "";
    }

    @Named("mapSummaries.geometry")
    Map mapSummariesGeometry(MDMetadataType source) {
        return createGeometryItems(
                source,
                GeometryUtils::createGeometryFromFromEXBoundingPolygonType,
                GeometryUtils::createGeometryFromEXGeographicBoundingBoxType
        );
    }

    @Named("mapSummaries.score")
    Integer createSummariesScore(MDMetadataType source) {
        //TODO: need cal logic
        return 0;
    }

    @Named("mapSummaries.status")
    String createSummariesStatus(MDMetadataType source) {
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if (!items.isEmpty()) {
            List<String> temp = new ArrayList<>();
            for (MDDataIdentificationType i : items) {
                // status
                // mdb:identificationInfo/mri:MD_DataIdentification/mri:status/mcc:MD_ProgressCode/@codeListValue
                for (MDProgressCodePropertyType s : i.getStatus()) {
                    temp.add(s.getMDProgressCode().getCodeListValue());
                }
            }
            return String.join(" | ", temp);
        }
        logger.warn("Unable to find status metadata record: " + this.mapUUID(source));
        return null;
    }

    @Named("mapSummaries.scope")
    Map<String, String> createSummariesScope(MDMetadataType source) {
        List<MDMetadataScopeType> items = findMDMetadataScopePropertyType(source);
        if (!items.isEmpty()) {
            for (MDMetadataScopeType i : items) {

                Map<String, String> result = new HashMap<>();
                CodeListValueType codeListValueType = i.getResourceScope().getMDScopeCode();
                result.put("code", codeListValueType != null ? codeListValueType.getCodeListValue() : "");
                CharacterStringPropertyType nameString = i.getName();
                result.put("name", nameString != null ? nameString.getCharacterString().getValue().toString() : "");

                return result;
            }
        }

        logger.warn("Unable to find scope metadata record: " + this.mapUUID(source));
        return null;
    }

    /**
     * Custom mapping for title field, name convention is start with map then the field name
     * @param source
     * @return
     */
    @Named("mapTitle")
    String mapTitle(MDMetadataType source) {
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if(!items.isEmpty()) {
            // Need to assert only 1 block contains our target
            for(MDDataIdentificationType i : items) {
                // TODO: Null or empty check
                AbstractCitationType ac = i.getCitation().getAbstractCitation().getValue();
                if(ac instanceof CICitationType2 type2) {
                    return type2.getTitle().getCharacterString().getValue().toString();
                }
                else if(ac instanceof CICitationType type1) {
                    // Backward compatible
                    type1.getTitle().getCharacterString().getValue().toString();
                }
            }
        }
        return "";
    }

    protected List<Map<String, String>> mapThemesConcepts(MDKeywordsPropertyType descriptiveKeyword) {
        List<Map<String, String>> keywords = new ArrayList<>();
        descriptiveKeyword.getMDKeywords().getKeyword().forEach(keyword -> {
            if (keyword != null) {
                if (keyword.getCharacterString().getValue() instanceof AnchorType value) {
                    keywords.add(Map.of("id", value.getValue(),
                            "url", value.getHref()));
                } else {
                    keywords.add(Map.of("id", keyword.getCharacterString().getValue().toString()));
                }
            }
        });
        return keywords;
    }

    protected String mapThemesTitle(MDKeywordsPropertyType descriptiveKeyword, String uuid) {
        AbstractCitationPropertyType abstractCitationPropertyType = descriptiveKeyword.getMDKeywords().getThesaurusName();
        if (abstractCitationPropertyType != null) {
            CICitationType2 thesaurusNameType2 = (CICitationType2) abstractCitationPropertyType.getAbstractCitation().getValue();
            CharacterStringPropertyType titleString = thesaurusNameType2.getTitle();
            if (titleString != null && titleString.getCharacterString().getValue() instanceof  AnchorType value) {
                if (value.getValue() != null) {
                    return value.getValue();
                } else {
                    return "";
                }
            } else if (titleString != null && titleString.getCharacterString().getValue() instanceof String value) {
                return value;
            }
        }
        logger.debug("Unable to find themes' title for metadata record: " + uuid);
        return "";
    }

    protected String mapThemesDescription(MDKeywordsPropertyType descriptiveKeyword, String uuid) {
        AbstractCitationPropertyType abstractCitationPropertyType = descriptiveKeyword.getMDKeywords().getThesaurusName();
        if (abstractCitationPropertyType != null) {
            CICitationType2 thesaurusNameType2 = (CICitationType2) abstractCitationPropertyType.getAbstractCitation().getValue();
            CharacterStringPropertyType titleString = thesaurusNameType2.getTitle();
            if (titleString != null && titleString.getCharacterString().getValue() instanceof  AnchorType value) {
                if (value.getTitleAttribute() != null) {
                    return value.getTitleAttribute();
                } else {
                    return "";
                }
            } else if (titleString != null && titleString.getCharacterString().getValue() instanceof String value) {
                return thesaurusNameType2.getAlternateTitle().stream().map(CharacterStringPropertyType::getCharacterString).map(JAXBElement::getValue).map(Object::toString).collect(Collectors.joining(", "));
            }
        }
        logger.debug("Unable to find themes' description for metadata record: " + uuid);
        return "";
    }

    protected String mapThemesScheme(MDKeywordsPropertyType descriptiveKeyword, String uuid) {
        AbstractCitationPropertyType abstractCitationPropertyType = descriptiveKeyword.getMDKeywords().getThesaurusName();
        if (abstractCitationPropertyType != null) {
            if (descriptiveKeyword.getMDKeywords().getType() != null) {
                return descriptiveKeyword.getMDKeywords().getType().getMDKeywordTypeCode().getCodeListValue();
            } else {
                return "";
            }
        }
        logger.debug("Unable to find themes' scheme for metadata record: " + uuid);
        return "";
    }

    @Named("mapThemes")
    List<ThemesModel> mapThemes(MDMetadataType source) {
        List<ThemesModel> results = new ArrayList<>();
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if (!items.isEmpty()) {
            for (MDDataIdentificationType i : items) {
                i.getDescriptiveKeywords().forEach(descriptiveKeyword -> {
                    ThemesModel themesModel = ThemesModel.builder().build();
                    String uuid = this.mapUUID(source);

                    themesModel.setConcepts(mapThemesConcepts(descriptiveKeyword));

                    themesModel.setTitle(mapThemesTitle(descriptiveKeyword, uuid));
                    themesModel.setDescription(mapThemesDescription(descriptiveKeyword, uuid));
                    themesModel.setScheme(mapThemesScheme(descriptiveKeyword, uuid));
                    results.add(themesModel);
                });
            }
        }
        return results;
    }


    @Named("mapContacts")
    List<ContactsModel> mapContacts(MDMetadataType source) {
        List<ContactsModel> results = new ArrayList<>();
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if (!items.isEmpty()) {
            for (MDDataIdentificationType item : items) {
                item.getPointOfContact().forEach(poc -> {
                    AbstractResponsibilityType responsibilityType = poc.getAbstractResponsibility().getValue();
                    if (responsibilityType instanceof CIResponsibilityType2 ciResponsibility) {
                        ContactsModel contactsModel = ContactsModel.builder().build();
                        contactsModel.setRoles(mapContactsRole(ciResponsibility));

                        if (ciResponsibility.getParty().isEmpty()) {
                            logger.warn("Unable to find contact info for metadata record: " + this.mapUUID(source));
                        } else {
                            ciResponsibility.getParty().forEach(party -> {
                                contactsModel.setOrganization(mapContactsOrganization(party));
                                try {

                                    AtomicReference<String> name = new AtomicReference<>("");
                                    AtomicReference<String> position = new AtomicReference<>("");
                                    List<Map<String, Object>> addresses = new ArrayList<>();
                                    List<String> emailAddresses = new ArrayList<>();
                                    List<Map<String, String>> phones = new ArrayList<>();
                                    List<Map<String, String>> onlineResources = new ArrayList<>();

                                    ((CIOrganisationType2) party.getAbstractCIParty().getValue()).getIndividual().forEach(individual -> {

                                        name.set(mapContactsName(individual));
                                        position.set(mapContactsPosition(individual));

                                        individual.getCIIndividual().getContactInfo().forEach(contactInfo -> {
                                            contactInfo.getCIContact().getAddress().forEach(address -> {
                                                // addresses
                                                addresses.add(mapContactsAddress(address));
                                                // emails
                                                address.getCIAddress().getElectronicMailAddress().forEach(electronicMailAddress -> {
                                                    emailAddresses.add(mapContactsEmail(electronicMailAddress));
                                                });
                                                // phones
                                                contactInfo.getCIContact().getPhone().forEach(phone -> {
                                                    phones.add(mapContactsPhone(phone));
                                                });
                                                // online resources
                                                contactInfo.getCIContact().getOnlineResource().forEach(onlineResource -> {
                                                    onlineResources.add(mapContactsOnlineResource(onlineResource));
                                                });
                                            });
                                        });
                                    });

                                    contactsModel.setName(name.get());
                                    contactsModel.setPosition(position.get());
                                    contactsModel.setAddresses(addresses);
                                    contactsModel.setEmails(emailAddresses);
                                    contactsModel.setPhones(phones);
                                    contactsModel.setLinks(onlineResources);

                                } catch (Exception e) {
                                    logger.warn("Unable to find contact info for metadata record: " + this.mapUUID(source));
                                }
                            });
                            results.add(contactsModel);
                        }
                    }
                });
            }
        }
        return results;
    }

    protected String mapContactsRole(CIResponsibilityType2 ciResponsibility) {
        CodeListValueType roleCode = ciResponsibility.getRole().getCIRoleCode();
        if (roleCode != null) { return roleCode.getCodeListValue(); } else { return ""; }
    }

    protected String mapContactsOrganization(AbstractCIPartyPropertyType2 party) {
        String organisationString = party.getAbstractCIParty().getValue().getName().getCharacterString().getValue().toString();
        if (organisationString != null) { return organisationString; } else { return ""; }

    }

    protected String mapContactsName(CIIndividualPropertyType2 individual) {
        CharacterStringPropertyType nameString = individual.getCIIndividual().getName();
        if (nameString != null) { return individual.getCIIndividual().getName().getCharacterString().getValue().toString(); } else { return ""; }
    }

    protected String mapContactsPosition(CIIndividualPropertyType2 individual) {
        CharacterStringPropertyType positionString = individual.getCIIndividual().getPositionName();
        if (positionString != null) { return individual.getCIIndividual().getPositionName().getCharacterString().getValue().toString(); } else { return ""; }
    }

    protected Map<String, Object> mapContactsAddress(CIAddressPropertyType2 address) {
        Map<String, Object> addressItem = new HashMap<>();
        List<String> deliveryPoints = new ArrayList<>();

        address.getCIAddress().getDeliveryPoint().forEach(deliveryPoint -> {
            String deliveryPointString = deliveryPoint.getCharacterString().getValue().toString();
            deliveryPoints.add(deliveryPointString != null ? deliveryPointString : "");
        });
        addressItem.put("deliveryPoint", deliveryPoints);

        CharacterStringPropertyType cityString = address.getCIAddress().getCity();
        addressItem.put("city", cityString != null ? cityString.getCharacterString().getValue().toString() : "");

        CharacterStringPropertyType administrativeAreaString = address.getCIAddress().getAdministrativeArea();
        addressItem.put("administrativeArea", administrativeAreaString != null ? administrativeAreaString.getCharacterString().getValue().toString() : "");

        CharacterStringPropertyType postalCodeString = address.getCIAddress().getPostalCode();
        addressItem.put("postalCode", postalCodeString != null ? postalCodeString.getCharacterString().getValue().toString() : "");

        CharacterStringPropertyType countryString = address.getCIAddress().getCountry();
        addressItem.put("country", countryString != null ? countryString.getCharacterString().getValue().toString() : "");

        return addressItem;
    }

    protected String mapContactsEmail(CharacterStringPropertyType electronicMailAddress) {
        if (electronicMailAddress != null) {
            return electronicMailAddress.getCharacterString().getValue().toString();
        } else {
            return "";
        }
    }

    protected Map<String, String> mapContactsPhone(CITelephonePropertyType2 phone) {
        Map<String, String> phoneItem = new HashMap<>();

        CharacterStringPropertyType phoneString = phone.getCITelephone().getNumber();
        phoneItem.put("value", phoneString != null ? phoneString.getCharacterString().getValue().toString() : "");

        CodeListValueType phoneCode = phone.getCITelephone().getNumberType().getCITelephoneTypeCode();
        phoneItem.put("roles", phoneCode != null ? phoneCode.getCodeListValue() : "");

        return phoneItem;
    }

    protected Map<String, String> mapContactsOnlineResource(CIOnlineResourcePropertyType2 onlineResource) {
        Map<String, String> onlineResourceItem = new HashMap<>();

        CharacterStringPropertyType linkString = onlineResource.getCIOnlineResource().getLinkage();
        onlineResourceItem.put("href", linkString != null ? linkString.getCharacterString().getValue().toString() : "");

        CharacterStringPropertyType resourceNameString = onlineResource.getCIOnlineResource().getName();
        onlineResourceItem.put("title", resourceNameString != null ? resourceNameString.getCharacterString().getValue().toString() : "");

        CharacterStringPropertyType linkTypeString = onlineResource.getCIOnlineResource().getProtocol();
        onlineResourceItem.put("type", linkTypeString != null ? linkTypeString.getCharacterString().getValue().toString() : "");

        return onlineResourceItem;
    }

    @Named("mapLanguages")
    protected List<LanguageModel> mapLanguages(MDMetadataType source) {
        List<LanguageModel> results = new ArrayList<>();
        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if (!items.isEmpty()) {
            for (MDDataIdentificationType i : items) {

                LanguageModel languageModel = LanguageModel.builder().build();

                String langCode = mapLanguagesCode(i) != null ? mapLanguagesCode(i) : "";
                languageModel.setCode(langCode);

                // all metadata records are in English anyway
                switch (langCode) {
                    case "eng" -> languageModel.setName("English");
                    case "fra" -> languageModel.setName("French");
                    default -> {
                        logger.warn("Making assumption...unable to find language name for metadata record: " + this.mapUUID(source));
                        languageModel.setCode("eng");
                        languageModel.setName("English");
                    }
                }

                results.add(languageModel);
            }
        }
        return results;
    }

    protected String mapLanguagesCode(MDDataIdentificationType i) {
        try {
            return i.getDefaultLocale().getPTLocale().getValue().getLanguage().getLanguageCode().getCodeListValue();
        } catch (NullPointerException e) {
            return null;
        }
    }


    protected <R> R createGeometryItems(
            MDMetadataType source,
            Function<List<Object>, R> exBoundingPolygonTypeHandler,
            Function<List<Object>, R> exGeographicBoundingBoxTypeHandler) {

        List<MDDataIdentificationType> items = findMDDataIdentificationType(source);
        if(!items.isEmpty()) {
            // Need to assert only 1 block contains our target
            for(MDDataIdentificationType i : items) {
                // We only concern geographicElement here
                List<EXExtentType> ext = i.getExtent()
                        .stream()
                        .filter(f -> f.getAbstractExtent() != null)
                        .filter(f -> f.getAbstractExtent().getValue() != null)
                        .filter(f -> f.getAbstractExtent().getValue() instanceof EXExtentType)
                        .map(f -> (EXExtentType)f.getAbstractExtent().getValue())
                        .filter(f -> f.getGeographicElement() != null)
                        .toList();

                for(EXExtentType e : ext) {
                    try {
                        // TODO: pay attention here
                        List<Object> rawInput = e.getGeographicElement()
                                .stream()
                                .map(AbstractEXGeographicExtentPropertyType::getAbstractEXGeographicExtent)
                                .filter(m -> m.getValue() instanceof EXBoundingPolygonType || m.getValue() instanceof EXGeographicBoundingBoxType)
                                .map(m -> {
                                    if (m.getValue() instanceof EXBoundingPolygonType) {
                                        return (EXBoundingPolygonType) m.getValue();
                                    } else if (m.getValue() instanceof EXGeographicBoundingBoxType) {
                                        return (EXGeographicBoundingBoxType) m.getValue();
                                    }
                                    return null; // Handle other cases or return appropriate default value
                                })
                                .filter(Objects::nonNull) // Filter out null values if any
                                .collect(Collectors.toList());

                        if (!rawInput.isEmpty() && rawInput.get(0) instanceof EXBoundingPolygonType) {
                            return exBoundingPolygonTypeHandler.apply(rawInput);
                        }
                        else if (!rawInput.isEmpty() && rawInput.get(0) instanceof EXGeographicBoundingBoxType) {
                            return exGeographicBoundingBoxTypeHandler.apply(rawInput);
                        }
                    }
                    catch (MappingValueException ex) {
                        logger.warn(ex.getMessage() + " for metadata record: " + this.mapUUID(source));
                    }
                }
            }
        }
        return null;
    }

    protected List<MDDataIdentificationType> findMDDataIdentificationType(MDMetadataType source) {
        // Read the raw XML to understand the structure.
        return source.getIdentificationInfo()
                .stream()
                .filter(f -> f.getAbstractResourceDescription() != null)
                .filter(f -> f.getAbstractResourceDescription().getValue() != null)
                .filter(f -> f.getAbstractResourceDescription().getValue() instanceof MDDataIdentificationType)
                .map(f -> (MDDataIdentificationType)f.getAbstractResourceDescription().getValue())
                .collect(Collectors.toList());
    }

    protected List<MDMetadataScopeType> findMDMetadataScopePropertyType(MDMetadataType source) {
        return source.getMetadataScope()
                .stream()
                .map(MDMetadataScopePropertyType::getMDMetadataScope)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
