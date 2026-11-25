#pragma once
// The Android app's native side: Compendium's core (CPK writer, mod detection, library) behind
// one JSON call, so the Kotlin UI never touches C++ types. Every call blocks until done and runs
// on the caller's thread; call it from a background thread. Library changes are serialized.
#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "http.hpp"
#include "installer.hpp"
#include "library.hpp"
#include "gamebanana.hpp"
#include "gamefiles.hpp"

namespace android {

// (task id, text, 0..1 or -1 when unknown)
using ProgressSink = std::function<void(const std::string&, const std::string&, float)>;

class Engine {
public:
    Engine(std::unique_ptr<mm::HttpClient> http, ProgressSink sink);
    // {"ok":true,"data":...} or {"ok":false,"error":"..."}.
    std::string call(const std::string& method, const std::string& argsJson);
    void cancel(const std::string& task);

private:
    struct Prepared {
        mm::gb::ModDetails details;
        mm::gb::ModFile file;
        mm::PreparedArchive prep;
        mm::fs::path work;
    };

    std::shared_ptr<std::atomic<bool>> taskFlag(const std::string& task);
    void progress(const std::string& task, const std::string& text, float pct);

    mm::fs::path sdRoot_, dataDir_;
    mm::fs::path downloadsDir() const { return dataDir_ / "downloads"; }
    mm::fs::path workDir() const { return dataDir_ / "work"; }
    mm::fs::path inboxDir() const { return dataDir_ / "inbox"; }
    mm::fs::path archivePathFor(const mm::gb::ModFile& f) const;
    bool isDownloaded(const mm::gb::ModFile& f) const;
    mm::PathResolver pathResolver() const;
    void loadFileList();
    mm::gb::ModDetails resolveLinkImpl(const std::string& url);
    std::shared_ptr<Prepared> takePrepared(const std::string& token, bool erase);

    std::unique_ptr<mm::HttpClient> http_;
    std::unique_ptr<mm::gb::Client> gb_;
    std::unique_ptr<mm::Library> library_;
    ProgressSink sink_;
    std::mutex libMutex_;  // the Library, and file_/prepared_ below
    std::mutex stateMutex_;
    std::shared_ptr<mm::GameFileList> fileList_;
    std::map<std::string, std::shared_ptr<Prepared>> prepared_;
    std::map<std::string, std::shared_ptr<std::atomic<bool>>> tasks_;
    bool hideNsfw_ = true;
    int nextToken_ = 1;
    friend struct EngineImpl;
};

}  // namespace android
