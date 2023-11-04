package info7255.controller;


import info7255.service.UserServiceImp;
import info7255.service.OAuth2;
import info7255.service.UserService;
import info7255.validator.JsonValidator;
import org.everit.json.schema.ValidationException;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.validation.Valid;
import javax.validation.constraints.Null;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;


@RestController
@RequestMapping(path = "/")
public class UserController {

    @Autowired
    JsonValidator validator;

    @Autowired
    UserService planservice;

    @Autowired
    private UserServiceImp userService;

    @Autowired
    OAuth2 authorizeService;

    @GetMapping(value = "/token")
    public ResponseEntity<String> getToken()
            throws UnsupportedEncodingException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        String token = authorizeService.getToken();
        System.out.println("token is "+token);
        return ResponseEntity.status(HttpStatus.CREATED).body(new JSONObject().put("Token", token).toString());
    }

    @PostMapping(path ="/plan", produces = "application/json")
    public ResponseEntity<Object> createPlan(@RequestHeader HttpHeaders headers, @Valid @RequestBody(required = false) String medicalPlan) throws Exception {
        if (medicalPlan == null || medicalPlan.isEmpty()){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error", "Request body is empty. Please provide a valid JSON.").toString());
        }

//        if(idToken == null || idToken.isEmpty())
//            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new JSONObject().put("Error", "No Token Found").toString());
//
//        //Authorize
//        if (!authorizeService.authorize(idToken.substring(7))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is invalid");

        String returnValue = authorizeService.authorizeToken(headers);
        if ((returnValue != "Valid Token"))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error: ", returnValue).toString());

        if (medicalPlan == null || medicalPlan.isEmpty()){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error", "Body is Empty.Kindly provide the JSON").toString());
        }


        JSONObject plan = new JSONObject(medicalPlan);
        try{
            validator.validateJson(plan);
        }catch(ValidationException ex){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error",ex.getErrorMessage()).toString());
        }

        String key = plan.get("objectType").toString() + "_" + plan.get("objectId").toString();
        //check if plan exists
        if(planservice.checkIfKeyExists(key)){
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new JSONObject().put("Message", "Plan already exist").toString());
        }

        //save the plan if not exist
        String newEtag = planservice.savePlanToRedisAndMQ(plan, key);
        String res = "{ObjectId: " + plan.get("objectId") + ", ObjectType: " + plan.get("objectType") + "}";
        return ResponseEntity.ok().eTag(newEtag).body(new JSONObject(res).toString());
    }


    @GetMapping(path = "/{type}/{objectId}",produces = "application/json ")
    public ResponseEntity<Object> getPlan(@RequestHeader HttpHeaders headers, @PathVariable String objectId,@PathVariable String type) throws JSONException, Exception {

        String returnValue = authorizeService.authorizeToken(headers);
        if ((returnValue != "Valid Token"))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error: ", returnValue).toString());

        String key = type + "_" + objectId;
        if (!planservice.checkIfKeyExists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        String actualEtag = null;
        if (type.equals("plan")) {
            actualEtag = planservice.getEtag(type + "_" + objectId, "eTag");
            String eTag = headers.getFirst("If-None-Match");
            if (eTag != null && eTag.equals(actualEtag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).body(new JSONObject().put("Message: ", "eTag doesn't  match").toString());
            }
        }

        Map<String, Object> plan = planservice.getPlan(key);
        if (type.equals("plan")) {
            return ResponseEntity.ok().eTag(actualEtag).body(new JSONObject(plan).toString());
        }

        return ResponseEntity.ok().body(new JSONObject(plan).toString());
    }

    @DeleteMapping("/plan/{objectId}")
    public ResponseEntity<Object> getPlan(@RequestHeader HttpHeaders headers,  @RequestHeader@PathVariable String objectId){
        String returnValue = authorizeService.authorizeToken(headers);
        if ((returnValue != "Valid Token"))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error: ", returnValue).toString());

        if (!planservice.checkIfKeyExists("plan"+ "_" + objectId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        String key = "plan_" + objectId;
        String actualEtag = planservice.getEtag(key, "eTag");
        String eTag = headers.getFirst("If-Match");

        if (eTag == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag not provided in header").toString());
        }

        if (eTag != null && !eTag.equals(actualEtag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag doesn't  match. This user can't use put").toString());
        }


        planservice.deletePlan("plan" + "_" + objectId);
//        planservice.deletePlan(objectId);

        userService.addToMessageQueue(true);
        return ResponseEntity.noContent().build();

    }

    @PutMapping(path = "/plan/{objectId}", produces = "application/json")
    public ResponseEntity<Object> updatePlan(@RequestHeader HttpHeaders headers, @Valid @RequestBody String medicalPlan, @PathVariable String objectId) throws IOException {
        String returnValue = authorizeService.authorizeToken(headers);
        if ((returnValue != "Valid Token"))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error: ", returnValue).toString());

        JSONObject planObject = new JSONObject(medicalPlan);
        try {
            validator.validateJson(planObject);
        } catch (ValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new JSONObject().put("Validation Error", ex.getMessage()).toString());
        }

        String key = "plan_" + objectId;
        if (!planservice.checkIfKeyExists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        // return status 412 if a mid-air update occurs (e.g. etag/header is different from etag/in-processing)
        String actualEtag = planservice.getEtag(key, "eTag");
        String eTag = headers.getFirst("If-Match");

        if (eTag == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag not provided in header").toString());
        }

        if (eTag != null && !eTag.equals(actualEtag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag doesn't  match. This user can't use put").toString());
        }


        String newEtag = planservice.savePlanToRedisAndMQ(planObject, key);

        return ResponseEntity.ok().eTag(newEtag)
                .body(new JSONObject().put("Message: ", "Resource updated successfully").toString());
    }

    @PatchMapping(path = "/plan/{objectId}", produces = "application/json")
    public ResponseEntity<Object> patchPlan(@RequestHeader HttpHeaders headers, @Valid @RequestBody String medicalPlan,
                                            @PathVariable String objectId) throws IOException {

        String returnValue = authorizeService.authorizeToken(headers);
        if ((returnValue != "Valid Token"))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error: ", returnValue).toString());

        JSONObject planObject = new JSONObject(medicalPlan);
        try {
            validator.validateJson(planObject);
        } catch (ValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new JSONObject().put("Validation Error", ex.getMessage()).toString());
        }

        if (!planservice.checkIfKeyExists("plan_" + objectId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        String key = planObject.get("objectType").toString() + "_" + planObject.get("objectId").toString();
        String actualEtag = planservice.getEtag(key, "eTag");
        String eTag = headers.getFirst("If-Match");

        if (eTag == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag not provided in header").toString());
        }

        if (eTag != null && !eTag.equals(actualEtag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(new JSONObject().put("Message: ", "eTag doesn't  match. This user can't use patch").toString());
        }


        planservice.deletePlan("plan" + "_" + objectId);
        String newEtag = planservice.savePlanToRedisAndMQ(planObject, key);

        return ResponseEntity.ok().eTag(newEtag)
                .body(new JSONObject().put("Message: ", "Resource updated successfully").toString());
    }




}
