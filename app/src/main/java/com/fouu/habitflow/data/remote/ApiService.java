package com.fouu.habitflow.data.remote;

import android.content.Context;
import android.util.Log;

import com.fouu.habitflow.BuildConfig;
import com.fouu.habitflow.R;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * ApiService - handles all network requests using OkHttp + Gson.
 *
 * AI Insights: calls the HabitFlow Cloudflare Worker (OpenAI-compatible
 * chat/completions), which proxies DeepSeek server-side. The app authenticates with
 * its own worker token (Authorization: Bearer WORKER_API_TOKEN); the real DeepSeek key
 * never ships in the client.
 * Other endpoints (quotes, sync) are placeholders for future backend.
 */
public class ApiService {

    private static final String TAG = "ApiService";
    private static final String WORKER_URL = BuildConfig.WORKER_BASE_URL;
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    // DeepSeek V4 enables "thinking mode" by default (high effort), which adds
    // latency + extra tokens for a trivial 2-3 sentence insight. We keep it OFF
    // for speed/cost; set to true if you want the model to reason before answering.
    private static final boolean DEEPSEEK_THINKING = false;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static ApiService instance;
    private final Context appContext;
    private final OkHttpClient client;
    private final Gson gson;

    private ApiService(Context context) {
        this.appContext = context.getApplicationContext();
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        this.client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .create();
    }

    public static synchronized ApiService getInstance(Context context) {
        if (instance == null) {
            instance = new ApiService(context.getApplicationContext());
        }
        return instance;
    }

    // ===== AI Insights (DeepSeek) =====

    /**
     * Generate AI-powered habit insights using DeepSeek Chat (OpenAI-compatible API).
     * Sends anonymized habit stats as a chat prompt, receives personalized recommendations.
     */
    public void generateInsights(String userId, String habitDataJson, ApiCallback<String> callback) {
        // Build the prompt with user's habit data (all copy resolved from resources)
        String systemPrompt = appContext.getString(R.string.ai_insight_system_prompt);
        String userPrompt = appContext.getString(R.string.ai_insight_user_prompt, habitDataJson);
        String parseError = appContext.getString(R.string.ai_insight_parse_error);

        // DeepSeek is OpenAI-compatible: messages = [system, user]
        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", DEEPSEEK_MODEL);
        requestBody.add("messages", messages);
        // Thinking mode toggle (OpenAI-compatible Chat Completion schema).
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", DEEPSEEK_THINKING ? "enabled" : "disabled");
        requestBody.add("thinking", thinking);
        requestBody.addProperty("max_tokens", 500);
        requestBody.addProperty("temperature", 0.7);
        requestBody.addProperty("stream", false);

        RequestBody body = RequestBody.create(requestBody.toString(), JSON);
        Request request = new Request.Builder()
                .url(WORKER_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + BuildConfig.WORKER_API_TOKEN)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Worker generateInsights failed: " + e.getMessage());
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseBody = response.body().string();
                        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
                        // DeepSeek response: choices[0].message.content
                        String insightText = json
                                .getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString();
                        callback.onSuccess(insightText);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse Worker response: " + e.getMessage());
                        callback.onError(parseError);
                    }
                } else {
                    // Surface the real error body from the worker (e.g. invalid token, upstream error).
                    String errBody = "";
                    try {
                        errBody = response.body() != null ? response.body().string() : "";
                    } catch (Exception ignored) {
                    }
                    Log.e(TAG, "Worker HTTP " + response.code() + " " + errBody);
                    callback.onError("HTTP " + response.code()
                            + (errBody.isEmpty() ? "" : " " + errBody));
                }
            }
        });
    }

    // ===== Callback interface =====
    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }
}
