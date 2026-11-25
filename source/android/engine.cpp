#include "engine.hpp"

#include <sys/statvfs.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <new>
#include <regex>
#include <set>

#include "compat.hpp"
#include "cpk.hpp"
#include "log.hpp"
#include "md5.hpp"
#include "nlohmann/json.hpp"
#include "p5rcbt.hpp"
#include "smm.hpp"

namespace android {

using json = nlohmann::json;
using mm::gb::ModDetails;
using mm::gb::ModFile;
using mm::gb::ModSummary;

namespace {

constexpr mm::Game kGame = mm::Game::P5R;

struct Cancelled : std::runtime_error {
    Cancelled() : std::runtime_error("Cancelled") {}
};

bool isLocalFile(const ModFile& f) { return f.downloadUrl.rfind("file://", 0) == 0; }

// ------------------------------------------------------------------ JSON <-> core types

json summaryToJson(const ModSummary& s) {
    return {{"id", s.id},
            {"sourceKey", s.sourceKey},
            {"gameId", s.gameId},
            {"name", s.name},
            {"author", s.author},
            {"category", s.category},
            {"thumbnailUrl", s.thumbnailUrl},
            {"profileUrl", s.profileUrl},
            {"dateAdded", s.dateAdded},
            {"dateModified", s.dateModified},
            {"likes", s.likes},
            {"views", s.views},
            {"nsfw", s.nsfw},
            {"obsolete", s.obsolete}};
}

ModSummary summaryFromJson(const json& j) {
    ModSummary s;
    s.id = j.value("id", int64_t{0});
    s.sourceKey = j.value("sourceKey", "");
    s.gameId = j.value("gameId", int64_t{0});
    s.name = j.value("name", "");
    s.author = j.value("author", "");
    s.category = j.value("category", "");
    s.thumbnailUrl = j.value("thumbnailUrl", "");
    s.profileUrl = j.value("profileUrl", "");
    s.dateAdded = j.value("dateAdded", int64_t{0});
    s.dateModified = j.value("dateModified", int64_t{0});
    s.likes = j.value("likes", int64_t{0});
    s.views = j.value("views", int64_t{0});
    s.nsfw = j.value("nsfw", false);
    s.obsolete = j.value("obsolete", false);
    return s;
}

json fileToJson(const ModFile& f) {
    return {{"id", f.id},           {"filename", f.filename}, {"size", f.size},        {"downloadUrl", f.downloadUrl},
            {"md5", f.md5},         {"version", f.version},   {"description", f.description},
            {"dateAdded", f.dateAdded}, {"avClean", f.avClean}};
}

ModFile fileFromJson(const json& j) {
    ModFile f;
    f.id = j.value("id", int64_t{0});
    f.filename = j.value("filename", "");
    f.size = j.value("size", int64_t{0});
    f.downloadUrl = j.value("downloadUrl", "");
    f.md5 = j.value("md5", "");
    f.version = j.value("version", "");
    f.description = j.value("description", "");
    f.dateAdded = j.value("dateAdded", int64_t{0});
    f.avClean = j.value("avClean", true);
    return f;
}

ModDetails detailsFromJson(const json& j) {
    ModDetails d;
    d.summary = summaryFromJson(j.value("summary", json::object()));
    d.version = j.value("version", "");
    d.descriptionHtml = j.value("descriptionHtml", "");
    d.downloads = j.value("downloads", int64_t{0});
    d.screenshotUrls = j.value("screenshotUrls", std::vector<std::string>{});
    for (const auto& f : j.value("files", json::array())) d.files.push_back(fileFromJson(f));
    return d;
}

// ------------------------------------------------------------------ which file suits the player

bool modUsesVersionWord(const ModDetails& d) {
    std::vector<std::string> labels;
    for (const auto& f : d.files) labels.push_back(f.description + " " + f.version);
    return mm::versionWordIsModVersion(labels, kGame);
}

struct FileNote {
    std::string text;
    std::string kind;  // ok, warn, muted
    bool matches = false;
};

// The installed game version isn't known on Android, so a file is never "for your game"; the
// notes still say which game versions a file names.
FileNote noteFor(const ModFile& f, bool versionWordIsMod) {
    if (mm::isRestoreOriginalsFile(f.filename, f.description)) return {"Original files · not needed", "muted", false};
    if (mm::saysAllVersions(f.description)) return {"Works on all game versions", "ok", true};
    auto named = mm::mentionedGameVersions(f.description, kGame, false, versionWordIsMod);
    if (!named.empty()) return {"For game " + named.front(), "muted", false};
    return {"", "muted", false};
}

size_t recommendedFile(const ModDetails& d) {
    size_t best = 0;
    int bestScore = -1000;
    bool versionWordIsMod = modUsesVersionWord(d);
    for (size_t i = 0; i < d.files.size(); ++i) {
        const auto& f = d.files[i];
        int score = 0;
        auto desc = mm::toLower(f.description + " " + f.filename);
        score += 2 * mm::languageMatch(f.description + " " + f.filename, "en");
        if (mm::isRestoreOriginalsFile(f.filename, f.description)) score -= 20;
        if (noteFor(f, versionWordIsMod).matches) score += 8;
        if (desc.find("switch") != std::string::npos || desc.find("nx") != std::string::npos) score += 3;
        if (desc.find("pc") != std::string::npos || desc.find("steam") != std::string::npos || desc.find("reloaded") != std::string::npos)
            score -= 2;
        if (desc.find("not recommend") != std::string::npos) score -= 2;
        static const std::regex mainFile(R"(\bmain\s+(file|download|mod)\b)");
        if (std::regex_search(desc, mainFile)) score += 1;
        bool isVariantOfAnother = false;
        for (size_t j = 0; j < d.files.size(); ++j)
            if (j != i && mm::isAddOnFileOf(f.filename, d.files[j].filename)) isVariantOfAnother = true;
        if (!isVariantOfAnother) score += 1;
        if (score > bestScore || (score == bestScore && f.dateAdded > d.files[best].dateAdded)) {
            best = i;
            bestScore = score;
        }
    }
    return best;
}

std::string modIdFor(const ModDetails& d, const ModFile& file) {
    if (d.summary.sourceKey.rfind("gb:", 0) == 0) return "gb" + std::to_string(d.summary.id) + "-" + std::to_string(file.id);
    return "src-" + mm::slugify(d.summary.sourceKey, 60) + "-" + std::to_string(file.id % 1000000007);
}

json detailsToJson(const ModDetails& d) {
    json files = json::array();
    bool vw = modUsesVersionWord(d);
    for (const auto& f : d.files) {
        auto j = fileToJson(f);
        auto n = noteFor(f, vw);
        j["note"] = n.text;
        j["noteKind"] = n.kind;
        files.push_back(std::move(j));
    }
    return {{"summary", summaryToJson(d.summary)},
            {"version", d.version},
            {"descriptionHtml", d.descriptionHtml},
            {"downloads", d.downloads},
            {"screenshotUrls", d.screenshotUrls},
            {"files", files},
            {"recommended", d.files.empty() ? 0 : recommendedFile(d)}};
}

json modToJson(const mm::ModRecord& m) {
    size_t count = m.cpkFiles.size() + m.sdFiles.size();
    return {{"id", m.id},
            {"name", m.name},
            {"author", m.author},
            {"version", m.version},
            {"sourceFile", m.sourceFile},
            {"profileUrl", m.profileUrl},
            {"sourceKey", m.sourceKey},
            {"installedAt", m.installedAt},
            {"variants", m.variants},
            {"format", m.format},
            {"pcMod", m.pcMod},
            {"notes", m.notes},
            {"enabled", m.enabled},
            {"priority", m.priority},
            {"fileCount", count},
            {"bundles", m.bundles.size()}};
}

json buildToJson(const mm::BuildStatus& b) {
    return {{"target", b.target}, {"files", b.files}, {"bytes", b.bytes}, {"withOriginals", b.withOriginals},
            {"missingOriginals", b.missingOriginals}};
}

mm::gb::Sort sortFromInt(int v) {
    switch (v) {
        case 1: return mm::gb::Sort::MostLiked;
        case 2: return mm::gb::Sort::Newest;
        case 3: return mm::gb::Sort::LatestUpdated;
        default: return mm::gb::Sort::MostDownloaded;
    }
}

json pageToJson(const mm::gb::Page& p, bool hideNsfw) {
    json mods = json::array();
    for (const auto& m : p.mods)
        if (!(hideNsfw && m.nsfw)) mods.push_back(summaryToJson(m));
    return {{"mods", mods}, {"totalCount", p.totalCount}, {"isLast", p.isLast}};
}

uint64_t freeSpaceOf(const mm::fs::path& root) {
    struct statvfs st {};
    auto s = root.string();
    if (statvfs(s.c_str(), &st) != 0) return 0;
    return static_cast<uint64_t>(st.f_bavail) * st.f_frsize;
}

bool isArchiveOrCpk(const std::string& name) { return mm::isSupportedArchiveName(name) || mm::endsWithI(name, ".cpk"); }

}  // namespace

// ------------------------------------------------------------------ Engine

Engine::Engine(std::unique_ptr<mm::HttpClient> http, ProgressSink sink)
    : http_(std::move(http)), gb_(std::make_unique<mm::gb::Client>(*http_)), sink_(std::move(sink)) {}

std::shared_ptr<std::atomic<bool>> Engine::taskFlag(const std::string& task) {
    std::lock_guard lock(stateMutex_);
    auto& f = tasks_[task];
    if (!f) f = std::make_shared<std::atomic<bool>>(false);
    return f;
}

void Engine::cancel(const std::string& task) {
    std::lock_guard lock(stateMutex_);
    auto it = tasks_.find(task);
    if (it != tasks_.end()) *it->second = true;
}

void Engine::progress(const std::string& task, const std::string& text, float pct) {
    if (sink_ && !task.empty()) sink_(task, text, pct);
}

mm::fs::path Engine::archivePathFor(const ModFile& f) const {
    if (isLocalFile(f)) return f.downloadUrl.substr(7);
    return downloadsDir() / (std::to_string(f.id) + "-" + mm::slugify(f.filename, 80));
}

bool Engine::isDownloaded(const ModFile& f) const {
    std::error_code ec;
    auto size = mm::fs::file_size(archivePathFor(f), ec);
    return !ec && size > 0 && (f.size <= 0 || static_cast<int64_t>(size) == f.size);
}

mm::PathResolver Engine::pathResolver() const {
    std::shared_ptr<mm::GameFileList> list;
    {
        std::lock_guard lock(const_cast<std::mutex&>(stateMutex_));
        list = fileList_;
    }
    if (!list || list->empty()) return {};
    return [list](const std::string& p) { return list->resolve(p); };
}

void Engine::loadFileList() {
    auto list = std::make_shared<mm::GameFileList>();
    std::lock_guard lock(stateMutex_);
    fileList_ = list->load(dataDir_ / "original" / "p5r" / "files.txt") ? list : nullptr;
}

std::string Engine::call(const std::string& method, const std::string& argsJson) {
    json args = json::parse(argsJson.empty() ? "{}" : argsJson, nullptr, false);
    if (!args.is_object()) args = json::object();
    std::string task = args.value("task", "");
    try {
        json data;
        const auto tid = std::string(mm::gameInfo(kGame).titleId);

        auto snapshot = [&]() -> json {  // libMutex_ held
            json mods = json::array(), conflicts = json::array();
            for (const auto* m : library_->mods(kGame)) mods.push_back(modToJson(*m));
            for (const auto& c : library_->conflicts(kGame))
                conflicts.push_back({{"path", c.path}, {"modIds", c.modIds}, {"inCpk", c.inCpk}});
            json inbox = json::array(), smm = json::array();
            std::error_code ec;
            std::vector<mm::fs::path> files;
            for (mm::fs::directory_iterator it(inboxDir(), ec), end; it != end; it.increment(ec)) {
                auto name = it->path().filename().string();
                if (it->is_regular_file(ec) && name.rfind("._", 0) != 0 && isArchiveOrCpk(name)) files.push_back(it->path());
            }
            std::sort(files.begin(), files.end());
            for (const auto& p : files) {
                std::error_code e2;
                inbox.push_back({{"name", p.filename().string()}, {"path", p.string()}, {"size", mm::fs::file_size(p, e2)}});
            }
            for (const auto& f : mm::smm::findMods(sdRoot_, kGame)) smm.push_back({{"name", f.name}, {"dir", f.dir.string()}});
            std::set<std::string> keys;
            for (const auto* m : library_->mods(kGame)) keys.insert(m->sourceKey);
            return {{"mods", mods},
                    {"conflicts", conflicts},
                    {"build", buildToJson(library_->buildStatus(kGame))},
                    {"mode", std::string(mm::deployModeKey(library_->mode(kGame)))},
                    {"unmanaged", library_->unmanagedOutput(kGame)},
                    {"haveOriginals", library_->hasOriginalPatch(kGame)},
                    {"p5rcbtInstalled", mm::p5rcbt::installed(sdRoot_, kGame)},
                    {"inbox", inbox},
                    {"smm", smm},
                    {"installedKeys", keys},
                    {"freeSpace", freeSpaceOf(sdRoot_)},
                    {"sdRoot", sdRoot_.string()},
                    {"contentsDir", mm::contentsDir(kGame)},
                    {"titleId", tid}};
        };

        auto libProgress = [&](uint64_t done, uint64_t total, const std::string& what) {
            float pct = total ? float(done) / float(total) : -1;
            std::string text = what.rfind("Building ", 0) == 0 ? what + "…" : "Working…";
            progress(task, text, pct);
        };

        auto requireLib = [&] {
            if (!library_) throw std::runtime_error("The storage folder isn't set up yet.");
        };

        // ---------------------------------------------------------------- setup
        if (method == "init") {
            std::lock_guard lock(libMutex_);
            sdRoot_ = args.value("sdRoot", "");
            if (sdRoot_.empty()) throw std::runtime_error("No storage folder chosen.");
            dataDir_ = sdRoot_ / "switch" / "Compendium";
            mm::fs::create_directories(dataDir_);
            std::error_code ec;
            mm::fs::create_directories(inboxDir(), ec);
            mm::log::close();
            mm::log::setClock([] {
                std::time_t t = std::time(nullptr);
                std::tm tm{};
                localtime_r(&t, &tm);
                char buf[32];
                std::strftime(buf, sizeof buf, "%Y-%m-%d %H:%M:%S", &tm);
                return std::string(buf);
            });
            mm::log::open(dataDir_ / "log.txt");
            mm::log::info("---- Compendium for Android " + args.value("version", "") + " started · " + sdRoot_.string());
            library_ = std::make_unique<mm::Library>(sdRoot_, dataDir_);
            auto root = sdRoot_;
            library_->setFreeSpace([root] { return freeSpaceOf(root); });
            loadFileList();
            library_->setPathResolver(pathResolver());
            mm::removeTree(workDir());  // leftovers of an install that was interrupted
            library_->load();
            if (library_->pathsFixedOnLoad() || library_->outdatedBuild()) {
                try {
                    progress(task, "Updating the game's mod files…", -1);
                    library_->apply();
                } catch (const std::exception& e) {
                    mm::log::error(std::string("updating the built archive: ") + e.what());
                }
            }
            data = snapshot();
        } else if (method == "snapshot") {
            std::lock_guard lock(libMutex_);
            requireLib();
            data = snapshot();

        // ---------------------------------------------------------------- GameBanana
        } else if (method == "gbList") {
            auto g = mm::gameInfo(kGame);
            auto id = args.value("pc", false) ? g.gamebananaPcId : g.gamebananaId;
            auto page = gb_->list(id, sortFromInt(args.value("sort", 0)), args.value("page", 1), 20, args.value("category", int64_t{0}));
            data = pageToJson(page, hideNsfw_);
        } else if (method == "gbSearch") {
            auto g = mm::gameInfo(kGame);
            auto id = args.value("pc", false) ? g.gamebananaPcId : g.gamebananaId;
            data = pageToJson(gb_->search(id, args.value("query", ""), args.value("page", 1)), hideNsfw_);
        } else if (method == "gbCategories") {
            auto g = mm::gameInfo(kGame);
            auto id = args.value("pc", false) ? g.gamebananaPcId : g.gamebananaId;
            data = json::array();
            for (const auto& c : gb_->categories(id)) data.push_back({{"id", c.id}, {"name", c.name}});
        } else if (method == "gbDetails") {
            auto d = gb_->details(args.value("id", int64_t{0}));
            if (d.summary.nsfw && hideNsfw_) throw std::runtime_error("GameBanana marks this mod as NSFW, so it's hidden.");
            data = detailsToJson(d);
        } else if (method == "resolveLink") {
            data = detailsToJson(resolveLinkImpl(args.value("url", "")));
        } else if (method == "localFile") {
            mm::fs::path path = args.value("path", "");
            std::error_code ec;
            if (!mm::fs::exists(path, ec)) throw std::runtime_error("That file isn't there any more.");
            ModDetails d;
            auto name = path.filename().string();
            bool dir = mm::fs::is_directory(path, ec);
            d.summary.sourceKey = (args.value("smm", false) ? "smm:" : "file:") + (args.value("smm", false) ? path.string() : name);
            d.summary.name = dir ? name : path.stem().string();
            d.summary.author = args.value("smm", false) ? "SimpleModManager" : "This device";
            ModFile f;
            f.filename = name;
            f.downloadUrl = "file://" + path.string();
            f.size = dir ? 0 : static_cast<int64_t>(mm::fs::file_size(path, ec));
            f.id = mm::stableId(path.string() + ":" + std::to_string(f.size));
            f.description = args.value("smm", false) ? "From SimpleModManager" : "From this device";
            d.files.push_back(f);
            data = detailsToJson(d);

        // ---------------------------------------------------------------- installing
        } else if (method == "prepareInstall") {
            auto d = detailsFromJson(args.value("details", json::object()));
            auto fileId = args.value("fileId", int64_t{0});
            auto it = std::find_if(d.files.begin(), d.files.end(), [&](const ModFile& f) { return f.id == fileId; });
            if (it == d.files.end()) throw std::runtime_error("That file isn't part of this mod any more.");
            auto file = *it;
            if (!file.avClean) throw std::runtime_error("GameBanana's virus scan flagged this file, so it won't be installed.");
            auto cancel = taskFlag(task);
            bool cached = isLocalFile(file) || isDownloaded(file);
            {
                std::lock_guard lock(libMutex_);
                requireLib();
            }
            uint64_t free = freeSpaceOf(sdRoot_);
            if (file.size > 0 && free > 0) {
                uint64_t need = static_cast<uint64_t>(file.size) * (cached ? 3 : 4);
                if (free < need)
                    throw std::runtime_error("Installing this needs about " + mm::formatSize(need) + " free in the storage folder, and there's " +
                                             mm::formatSize(free) + ". Remove some mods or other files and try again.");
            }
            auto archive = archivePathFor(file);
            mm::log::info("install \"" + d.summary.name + "\" (" + d.summary.sourceKey + "), file " + file.filename +
                          (cached ? " (already downloaded)" : " from " + file.downloadUrl));
            if (!cached) {
                mm::fs::create_directories(downloadsDir());
                int lastPct = -1;
                try {
                    http_->download(file.downloadUrl, archive, [&](uint64_t done, uint64_t total) {
                        if (!total && file.size > 0) total = static_cast<uint64_t>(file.size);
                        int pct = total ? static_cast<int>(done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress(task, pct >= 0 ? "Downloading… " + std::to_string(pct) + "%" : "Downloading… " + mm::formatSize(done),
                                     pct >= 0 ? pct / 100.0f : -1);
                        }
                        return !*cancel;
                    });
                } catch (...) {
                    std::error_code ec;
                    mm::fs::remove(archive, ec);
                    throw;
                }
                if (!file.md5.empty()) {
                    progress(task, "Checking download…", -1);
                    if (!mm::iequals(mm::md5File(archive), file.md5)) {
                        std::error_code ec;
                        mm::fs::remove(archive, ec);
                        throw std::runtime_error("The download was damaged (its checksum doesn't match GameBanana's). Try again.");
                    }
                }
            }
            if (*cancel) throw Cancelled();
            auto work = workDir() / std::to_string(file.id);
            auto prepared = std::make_shared<Prepared>();
            prepared->details = d;
            prepared->file = file;
            prepared->work = work;
            progress(task, "Unpacking… 0%", 0);
            int lastPct = -1;
            try {
                prepared->prep = mm::prepareArchive(archive, work, kGame, pathResolver(), [&](uint64_t done, uint64_t total, const std::string&) {
                    int pct = total ? static_cast<int>(done * 100 / total) : 0;
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress(task, "Unpacking… " + std::to_string(pct) + "%", pct / 100.0f);
                    }
                    return !*cancel;
                });
            } catch (...) {
                mm::removeTree(work);
                throw;
            }
            if (*cancel) {
                mm::removeTree(work);
                throw Cancelled();
            }
            const auto& layout = prepared->prep.layout;
            json variants = json::array(), selected = json::array();
            for (size_t i = 0; i < layout.variants.size(); ++i) {
                const auto& v = layout.variants[i];
                size_t count = v.files.size() + v.sdFiles.size();
                for (const auto& c : v.cpks) count += c.files;
                variants.push_back({{"name", v.name}, {"format", std::string(mm::formatName(v.format))}, {"files", count}});
                if (mm::checkCompat("", mm::mentionedGameVersions(v.name, kGame)) == mm::Compat::Likely) selected.push_back(i);
            }
            if (selected.empty() && !layout.variants.empty()) selected.push_back(0);
            std::string token;
            if (layout.variants.empty()) {
                mm::removeTree(work);
            } else {
                std::lock_guard lock(stateMutex_);
                token = "p" + std::to_string(nextToken_++);
                prepared_[token] = prepared;
            }
            data = {{"token", token}, {"variants", variants}, {"selected", selected},
                    {"warnings", layout.warnings}, {"problem", layout.problem}, {"pcMod", layout.pcMod}};
        } else if (method == "installConflicts") {
            auto prep = takePrepared(args.value("token", ""), false);
            std::vector<size_t> chosen = args.value("variants", std::vector<size_t>{});
            auto newId = modIdFor(prep->details, prep->file);
            std::lock_guard lock(libMutex_);
            requireLib();
            json same = json::array();
            std::vector<std::string> clashing;
            std::set<std::string> paths;
            auto set = mm::mergeVariants(prep->prep.layout, chosen);
            for (const auto& f : set.files) paths.insert(mm::cpk::pathKey(f.cpkPath));
            for (const auto& c : set.cpks) try {
                    for (const auto& e : mm::cpk::read(c.cpk).entries) paths.insert(mm::cpk::pathKey(e.path()));
                } catch (...) {
                }
            for (const auto* m : library_->mods(kGame)) {
                if (!prep->details.summary.sourceKey.empty() && m->sourceKey == prep->details.summary.sourceKey && m->id != newId)
                    same.push_back({{"id", m->id}, {"name", m->sourceFile}});
            }
            for (const auto* m : library_->mods(kGame)) {
                if (!m->enabled || m->id == newId) continue;
                for (const auto& p : m->cpkFiles)
                    if (paths.count(mm::cpk::pathKey(p))) {
                        clashing.push_back(m->name);
                        break;
                    }
            }
            data = {{"sameMod", same}, {"clashing", clashing}};
        } else if (method == "commitInstall") {
            auto prep = takePrepared(args.value("token", ""), true);
            std::vector<size_t> chosen = args.value("variants", std::vector<size_t>{});
            std::vector<std::string> replace = args.value("replace", std::vector<std::string>{});
            if (chosen.empty()) throw std::runtime_error("Pick at least one option.");
            const auto& d = prep->details;
            const auto& file = prep->file;
            const auto& layout = prep->prep.layout;
            mm::ModRecord r;
            r.id = modIdFor(d, file);
            r.game = kGame;
            r.name = d.summary.name;
            r.author = d.summary.author;
            r.version = file.version.empty() ? d.version : file.version;
            r.sourceFile = file.filename;
            r.profileUrl = d.summary.profileUrl;
            r.sourceKey = d.summary.sourceKey;
            if (d.summary.sourceKey.rfind("gb:", 0) == 0) {
                r.gamebananaModId = d.summary.id;
                r.gamebananaFileId = file.id;
            }
            if (layout.variants.size() > 1)
                for (auto i : chosen) r.variants.push_back(layout.variants.at(i).name);
            r.format = mm::formatName(layout.variants.at(chosen.front()).format);
            r.pcMod = layout.pcMod;
            r.notes = mm::installNotes(layout, chosen);
            auto files = mm::mergeVariants(layout, chosen);
            std::lock_guard lock(libMutex_);
            requireLib();
            progress(task, "Installing…", 0);
            try {
                for (const auto& id : replace) library_->remove(id);
                library_->add(r, files, true, libProgress);
            } catch (...) {
                mm::removeTree(prep->work);
                throw;
            }
            mm::removeTree(prep->work);
            if (!isLocalFile(file)) {
                std::error_code ec;
                mm::fs::remove(archivePathFor(file), ec);
            }
            data = {{"modId", r.id}, {"name", r.name}, {"notes", r.notes}, {"pcMod", r.pcMod},
                    {"build", buildToJson(library_->buildStatus(kGame))}};
        } else if (method == "discardPrepared") {
            try {
                auto p = takePrepared(args.value("token", ""), true);
                mm::removeTree(p->work);
            } catch (const std::exception&) {
            }
            data = json::object();

        // ---------------------------------------------------------------- the library
        } else if (method == "setEnabled") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->setEnabled(args.value("id", ""), args.value("enabled", false), libProgress);
            data = snapshot();
        } else if (method == "remove") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->remove(args.value("id", ""), libProgress);
            data = snapshot();
        } else if (method == "move") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->move(args.value("id", ""), args.value("delta", 0), libProgress);
            data = snapshot();
        } else if (method == "setMode") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->setMode(kGame, args.value("mode", "") == "p5rcbt" ? mm::DeployMode::P5rcbt : mm::DeployMode::Patch1, libProgress);
            data = snapshot();
        } else if (method == "rebuild") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->rebuild(kGame, libProgress);
            data = snapshot();
        } else if (method == "importUnmanaged") {
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->importUnmanagedOutput(kGame, args.value("name", "Mods from before"), libProgress);
            data = snapshot();
        } else if (method == "importGameCpks") {
            // The game's own CPKs (dumped from the emulator or the console): PATCH1.CPK is kept so
            // its files go into the built archive, and every CPK's file list lets the app place
            // mod files that come without their folder.
            std::vector<std::string> paths = args.value("paths", std::vector<std::string>{});
            std::lock_guard lock(libMutex_);
            requireLib();
            std::vector<std::string> all;
            std::string status;
            for (const auto& p : paths) {
                mm::fs::path src = p;
                auto info = mm::cpk::read(src);
                if (info.tool == "Compendium")
                    throw std::runtime_error(src.filename().string() + " was built by Compendium, not the game's own file.");
                for (const auto& e : info.entries) all.push_back(e.path());
                if (mm::iequals(src.filename().string(), "PATCH1.CPK")) {
                    auto orig = library_->originalPatchCpk(kGame);
                    mm::fs::create_directories(orig.parent_path());
                    auto tmp = orig;
                    tmp += ".new";
                    mm::copyFileCreatingDirs(src, tmp);
                    std::error_code ec;
                    mm::fs::remove(orig, ec);
                    mm::fs::rename(tmp, orig, ec);
                    if (ec) throw std::runtime_error("Couldn't keep the copy of PATCH1.CPK.");
                    status += "PATCH1.CPK kept. ";
                }
            }
            if (all.empty()) throw std::runtime_error("Those files hold no game files.");
            auto listFile = dataDir_ / "original" / "p5r" / "files.txt";
            mm::fs::create_directories(listFile.parent_path());
            mm::GameFileList::save(listFile, all);
            loadFileList();
            library_->setPathResolver(pathResolver());
            progress(task, "Building…", -1);
            library_->rebuild(kGame, libProgress);
            mm::log::info("read the game's own files: " + std::to_string(all.size()) + " paths. " + status);
            data = snapshot();
        } else if (method == "p5rcbtConfig") {
            auto c = mm::p5rcbt::readConfig(sdRoot_);
            if (args.contains("logging")) c.logging = args.value("logging", c.logging);
            if (args.contains("uncapFramerate")) c.uncapFramerate = args.value("uncapFramerate", c.uncapFramerate);
            if (args.contains("pcSettings")) c.pcSettings = args.value("pcSettings", c.pcSettings);
            if (args.contains("logging") || args.contains("uncapFramerate") || args.contains("pcSettings")) mm::p5rcbt::writeConfig(sdRoot_, c);
            data = {{"logging", c.logging}, {"uncapFramerate", c.uncapFramerate}, {"pcSettings", c.pcSettings}};
        } else if (method == "installP5rcbt") {
            auto release = mm::p5rcbt::parseRelease(http_->get(mm::p5rcbt::kReleaseApi));
            auto zip = downloadsDir() / ("p5rcbt-" + mm::slugify(release.tag) + ".zip");
            mm::fs::create_directories(downloadsDir());
            http_->download(release.zipUrl, zip, [&](uint64_t done, uint64_t total) {
                progress(task, "Downloading p5rcbt…", total ? float(done) / float(total) : -1);
                return true;
            });
            auto prep = mm::prepareArchive(zip, workDir() / "p5rcbt", kGame);
            std::vector<size_t> all;
            for (size_t i = 0; i < prep.layout.variants.size(); ++i) all.push_back(i);
            auto files = mm::mergeVariants(prep.layout, all);
            files.files.clear();
            files.cpks.clear();
            if (files.sdFiles.empty()) throw std::runtime_error("The p5rcbt download didn't contain the plugin.");
            mm::ModRecord rec;
            rec.id = mm::p5rcbt::kModId;
            rec.game = kGame;
            rec.name = "p5rcbt (Skyline plugin)";
            rec.author = "Raytwo";
            rec.version = release.tag;
            rec.sourceFile = zip.filename().string();
            rec.profileUrl = "https://github.com/Raytwo/p5rcbt";
            rec.sourceKey = "gh:Raytwo/p5rcbt";
            rec.format = "Plugin";
            std::lock_guard lock(libMutex_);
            requireLib();
            library_->add(rec, files, true, libProgress);
            mm::removeTree(workDir() / "p5rcbt");
            std::error_code ec;
            mm::fs::remove(zip, ec);
            data = snapshot();

        // ---------------------------------------------------------------- housekeeping
        } else if (method == "readLog") {
            std::string text;
            try {
                text = mm::readFile(dataDir_ / "log.txt");
            } catch (...) {
            }
            if (text.size() > 60000) text = text.substr(text.size() - 60000);
            data = {{"text", text}, {"path", (dataDir_ / "log.txt").string()}};
        } else if (method == "clearDownloads") {
            std::lock_guard lock(libMutex_);
            uint64_t bytes = 0;
            std::error_code ec;
            for (mm::fs::recursive_directory_iterator it(downloadsDir(), ec), end; it != end; it.increment(ec))
                if (it->is_regular_file(ec)) bytes += it->file_size(ec);
            mm::removeTree(downloadsDir());
            mm::removeTree(workDir());
            data = {{"bytes", bytes}};
        } else if (method == "deleteInboxFile") {
            mm::fs::path p = args.value("path", "");
            if (p.parent_path() != inboxDir()) throw std::runtime_error("That file isn't in the inbox.");
            std::error_code ec;
            mm::fs::remove(p, ec);
            data = json::object();
        } else {
            throw std::runtime_error("Unknown call: " + method);
        }
        {
            std::lock_guard lock(stateMutex_);
            tasks_.erase(task);
        }
        return json{{"ok", true}, {"data", data}}.dump();
    } catch (const Cancelled&) {
        std::lock_guard lock(stateMutex_);
        tasks_.erase(task);
        return json{{"ok", false}, {"error", "Cancelled"}, {"cancelled", true}}.dump();
    } catch (const std::bad_alloc&) {
        return json{{"ok", false}, {"error", "Compendium ran out of memory. Close other apps and try again."}}.dump();
    } catch (const mm::HttpStatusError& e) {
        mm::log::error(std::string(method) + ": " + e.what());
        return json{{"ok", false}, {"error", e.what()}, {"status", e.status()}}.dump();
    } catch (const std::exception& e) {
        {
            std::lock_guard lock(stateMutex_);
            auto it = tasks_.find(task);
            bool cancelled = it != tasks_.end() && *it->second;
            tasks_.erase(task);
            if (cancelled) return json{{"ok", false}, {"error", "Cancelled"}, {"cancelled", true}}.dump();
        }
        mm::log::error(method + ": " + e.what());
        return json{{"ok", false}, {"error", e.what()}}.dump();
    }
}

std::shared_ptr<Engine::Prepared> Engine::takePrepared(const std::string& token, bool erase) {
    std::lock_guard lock(stateMutex_);
    auto it = prepared_.find(token);
    if (it == prepared_.end()) throw std::runtime_error("This install was cancelled or timed out. Start it again.");
    auto p = it->second;
    if (erase) prepared_.erase(it);
    return p;
}

// A pasted link or a GameBanana number -> something installable. Throws with a message.
ModDetails Engine::resolveLinkImpl(const std::string& rawUrl) {
    std::string url = rawUrl;
    while (!url.empty() && (url.back() == ' ' || url.back() == '\n' || url.back() == '\r')) url.pop_back();
    while (!url.empty() && (url.front() == ' ' || url.front() == '\n')) url.erase(url.begin());
    if (!url.empty() && url.find_first_not_of("0123456789") == std::string::npos) url = "https://gamebanana.com/mods/" + url;
    static const std::regex gbRe(R"(^https?://(www\.)?gamebanana\.com/(mods|dl)/(\d+))", std::regex::icase);
    std::smatch m;
    if (std::regex_search(url, m, gbRe) && m[2].str() == "mods") {
        ModDetails d;
        try {
            d = gb_->details(std::stoll(m[3].str()));
        } catch (const mm::HttpStatusError& e) {
            if (e.status() == 404) throw std::runtime_error("There's no GameBanana mod with that number.");
            throw;
        }
        if (d.summary.gameId && d.summary.gameId != mm::gameInfo(kGame).gamebananaId)
            d.summary.category = "Other version of the game: " + d.summary.category;
        if (d.summary.nsfw && hideNsfw_) throw std::runtime_error("GameBanana marks this mod as NSFW, so it's hidden.");
        return d;
    }
    static const std::regex ghRe(R"(^https?://github\.com/([^/]+)/([^/#?]+)/?(releases/?(latest)?)?/?$)", std::regex::icase);
    if (std::regex_search(url, m, ghRe)) {
        auto body = http_->get("https://api.github.com/repos/" + m[1].str() + "/" + m[2].str() + "/releases/latest");
        auto j = json::parse(body, nullptr, false);
        ModDetails d;
        d.summary.sourceKey = "gh:" + m[1].str() + "/" + m[2].str();
        d.summary.id = mm::stableId(d.summary.sourceKey);
        d.summary.name = m[2].str();
        d.summary.author = m[1].str();
        d.summary.profileUrl = "https://github.com/" + m[1].str() + "/" + m[2].str();
        if (j.is_object()) {
            d.version = j.value("tag_name", "");
            d.descriptionHtml = j.value("body", "");
            for (const auto& a : j.value("assets", json::array())) {
                auto name = a.value("name", "");
                if (!mm::isSupportedArchiveName(name) && !mm::endsWithI(name, ".cpk")) continue;
                ModFile f;
                f.filename = name;
                f.downloadUrl = a.value("browser_download_url", "");
                f.size = a.value("size", int64_t(0));
                f.id = mm::stableId(f.downloadUrl);
                f.version = d.version;
                d.files.push_back(f);
            }
        }
        if (d.files.empty()) throw std::runtime_error("That GitHub project's latest release has no archive to install.");
        return d;
    }
    if (url.rfind("http", 0) != 0) throw std::runtime_error("That doesn't look like a link or a GameBanana mod number.");
    std::string path = url.substr(0, url.find_first_of("?#"));
    std::string filename = mm::urlDecode(path.substr(path.rfind('/') + 1));
    if (filename.empty() || filename.find('.') == std::string::npos) filename = "download.zip";
    ModDetails d;
    d.summary.sourceKey = "url:" + path;
    d.summary.id = mm::stableId(d.summary.sourceKey);
    d.summary.name = filename.substr(0, filename.rfind('.'));
    auto host = url.find("//") + 2;
    d.summary.author = url.substr(host, url.find('/', host) - host);
    d.summary.profileUrl = url;
    ModFile f;
    f.id = mm::stableId(url);
    f.filename = filename;
    f.downloadUrl = url;
    f.size = http_->contentLength(url);
    f.description = "From a link";
    d.files.push_back(f);
    return d;
}

}  // namespace android
