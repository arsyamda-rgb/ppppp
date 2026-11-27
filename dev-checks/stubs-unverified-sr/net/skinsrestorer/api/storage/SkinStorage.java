package net.skinsrestorer.api.storage;
import java.util.Optional;
import net.skinsrestorer.api.property.*;
public interface SkinStorage {
    Optional<InputDataResult> findSkinData(String input, SkinVariant variant);
    Optional<InputDataResult> findOrCreateSkinData(String input, SkinVariant variant) throws Exception;
}
