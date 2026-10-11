package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import java.util.*;

/** Shared native System One / Decisions API codec. Reject incomplete distributions before dispatch. */
public final class DecisionProtocol {
    private DecisionProtocol() { }
    public static JsonObject request(String model, JsonObject state, JsonObject questions, DecisionProvider.Capabilities caps) {
        if(model == null || model.isBlank() || state == null || questions == null || questions.size() == 0
                || questions.size() > caps.maxQuestions()) throw new IllegalArgumentException("decision_request_limit");
        for(var entry : questions.entrySet()) {
            if(entry.getKey().isBlank()) throw new IllegalArgumentException("decision_question_id");
            var question = entry.getValue().getAsJsonObject();
            if(!question.has("instructions")) throw new IllegalArgumentException("decision_instructions_required");
            switch(question.get("type").getAsString()) {
                case "choice" -> {
                    int n=question.getAsJsonObject("criteria").size();
                    if(n<1 || n>caps.maxChoices()) throw new IllegalArgumentException("decision_choice_limit");
                }
                case "score" -> {
                    int n=question.getAsJsonArray("criteria").size();
                    if(n<2 || n>caps.maxScoreLevels()) throw new IllegalArgumentException("decision_score_limit");
                }
                case "noul" -> { }
                default -> throw new IllegalArgumentException("decision_question_type");
            }
        }
        JsonObject body=new JsonObject(); body.addProperty("model",model);
        body.add("state",state.deepCopy()); body.add("questions",questions.deepCopy());
        // A deliberately small operational budget keeps a decision inside the short request deadline.
        if(ContextTokenEstimator.estimate(body.toString())>8192) throw new IllegalArgumentException("decision_context_limit");
        return body;
    }
    public static DecisionProvider.Result response(String body, JsonObject questions, String requestId) {
        try {
            if(body.length()>1048576) throw new IllegalArgumentException();
            var root=JsonParser.parseString(body).getAsJsonObject();
            String model=root.get("model").getAsString(); if(model.isBlank()) throw new IllegalArgumentException();
            var answers=root.getAsJsonObject("answers");
            if(!answers.keySet().equals(questions.keySet())) throw new IllegalArgumentException();
            for(var entry:questions.entrySet()) {
                var q=entry.getValue().getAsJsonObject();var a=answers.getAsJsonObject(entry.getKey());
                String type=q.get("type").getAsString();
                if(!type.equals(a.get("type").getAsString())) throw new IllegalArgumentException();
                if(type.equals("noul")) { probability(a.get("noul"));continue; }
                probability(a.get("confidence"));
                Set<String> expected=new LinkedHashSet<>();
                if(type.equals("choice")) expected.addAll(q.getAsJsonObject("criteria").keySet());
                else for(int i=0;i<q.getAsJsonArray("criteria").size();i++) expected.add(Integer.toString(i));
                var probabilities=a.getAsJsonObject("probabilities");
                if(!expected.equals(probabilities.keySet())) throw new IllegalArgumentException();
                double sum=0,maximum=0,weighted=0;
                for(String key:expected) {
                    double p=probability(probabilities.get(key));sum+=p;maximum=Math.max(maximum,p);
                    if(type.equals("score")) weighted+=Integer.parseInt(key)*p;
                }
                if(Math.abs(sum-1)>0.005) throw new IllegalArgumentException();
                if(type.equals("choice")) {
                    String choice=a.get("choice").getAsString();
                    if(!expected.contains(choice) || probability(probabilities.get(choice))+1e-6<maximum) throw new IllegalArgumentException();
                } else {
                    if(!Double.isFinite(a.get("score").getAsDouble()) || Math.abs(a.get("score").getAsDouble()-weighted)>0.01) throw new IllegalArgumentException();
                    var legend=a.getAsJsonObject("legend");
                    if(!legend.keySet().equals(expected)) throw new IllegalArgumentException();
                    for(String key:expected) if(!legend.get(key).equals(q.getAsJsonArray("criteria").get(Integer.parseInt(key)))) throw new IllegalArgumentException();
                }
            }
            var usage=root.getAsJsonObject("usage");
            return new DecisionProvider.Result(model,answers.deepCopy(),tokens(usage,"input_tokens"),tokens(usage,"output_tokens"),requestId);
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("invalid_decision_response"); }
    }
    private static long tokens(JsonObject usage,String key) {
        if(usage==null || !usage.has(key)) return 0;
        double n=usage.get(key).getAsDouble();long value=usage.get(key).getAsLong();
        if(!Double.isFinite(n) || n!=value || value<0) throw new IllegalArgumentException();return value;
    }
    public static double probability(JsonElement value) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        double p=value.getAsDouble();if(!Double.isFinite(p) || p<0 || p>1) throw new IllegalArgumentException();return p;
    }
    public static JsonObject choice(String instruction, Map<String,String> criteria) {
        JsonObject q=new JsonObject(),c=new JsonObject();q.addProperty("type","choice");q.addProperty("instructions",instruction);
        criteria.forEach(c::addProperty);q.add("criteria",c);return q;
    }
    public static JsonObject noul(String instruction) {
        JsonObject q=new JsonObject();q.addProperty("type","noul");q.addProperty("instructions",instruction);return q;
    }
}
